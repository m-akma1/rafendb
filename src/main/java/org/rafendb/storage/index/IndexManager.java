package org.rafendb.storage.index;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.catalog.CatalogManager;
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.btree.BPlusTree;

/**
 * Builds, maintains and searches the Storage Manager's indexes (SM-10, SM-11): a B+ tree ({@link
 * IndexType#B_PLUS_TREE}) or a static hash ({@link IndexType#HASH}) on one column of one table.
 *
 * <p>Only the facade calls this class; other components reach indexes through {@link
 * StorageManager}. Names are passed already normalized. A {@link Rid} has no file part, so it only
 * identifies a record together with its table; every index belongs to exactly one table.
 *
 * <p>Each entry is a (key, Rid) pair ordered by key, then Rid. Duplicate keys are allowed, and an
 * entry identifies exactly one record, which makes insert and delete idempotent. NULL keys are not
 * indexed. Keys must be of the column's type family (INTEGER, FLOAT, or text, where CHAR and
 * VARCHAR are one family) and are never converted.
 *
 * <p>All arguments and results are non-null unless stated; null arguments throw {@link
 * NullPointerException}. Returned lists are unmodifiable. Invalid input throws {@link
 * StorageRuntimeException} for invalid index definitions before any change; storage failures
 * and invalid lookup/range calls throw {@link StorageInternalException}.
 *
 * <p>Writes take the LSN of the log record of the operation that caused them, and mark every
 * changed index page with it, including persisted root/split metadata. No root changes may live
 * only in a Java field. Unlogged create/rebuild produces derived pages through markUnloggedDirty;
 * it never manufactures an LSN and preserves WAL dependencies already present in shared frames.
 *
 * <p>A maintenance call applies to all of a table's indexes or to none: if one fails, the earlier
 * ones are undone before the error is thrown, because the facade then restores the table page and
 * does not repeat the call. An index whose state is no longer certain is damaged: searching it
 * throws, writes skip it, and only a rebuild brings it back.
 *
 * <p>Thread-safe: every public method holds this object's monitor, and no state is cached apart
 * from the damaged set. Index locks and page latches must not be held while re-entering the facade.
 * Isolation between transactions is CCM's job.
 *
 * <p>Not implemented yet: {@link IndexType#HASH} is rejected by {@link #create}, and {@link
 * #beginSession} and {@link #markClean} still throw {@link UnsupportedOperationException}.
 */
public class IndexManager {
    protected final CatalogManager catalog;
    protected final BlockManager blocks;
    protected final BufferManager buffers;
    private final Set<String> damaged = new HashSet<>();

    /** Wires the same catalog, physical files and buffer used by the storage facade. */
    public IndexManager(CatalogManager catalog, BlockManager blocks, BufferManager buffers) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.buffers = Objects.requireNonNull(buffers, "buffers");
    }

    /**
     * Reads the previous clean-shutdown marker, then durably marks this session unclean before
     * any index/table mutation. Returns true when the old marker is missing/unclean and all indexes
     * must be rebuilt. I/O/corruption failures are errors, never interpreted as a clean session.
     * The marker is a separate binary file in the database directory, owned only by this manager.
     */
    public boolean beginSession() throws StorageInternalException {
        // TODO SM5: Baca marker lama, force status tidak bersih, lalu laporkan kebutuhan rebuild.
        throw new UnsupportedOperationException("SM5: IndexManager.beginSession");
    }

    /**
     * Durably marks shutdown clean, idempotently. Called only after catalog.save and successful
     * buffer close/force, with facade mutations blocked. Uses its own marker file, not cached pages;
     * force atomic replacement and its directory entry. Regular flush/checkpoint does not call it.
     */
    public void markClean() throws StorageInternalException {
        // TODO SM5: Force marker bersih hanya setelah semua page/root dan katalog permanen.
        throw new UnsupportedOperationException("SM5: IndexManager.markClean");
    }

    /**
     * Builds an index from a table's live records, then registers it in the catalog as the last
     * step.
     *
     * <p>Rows whose key is NULL are skipped. The build is atomic: if any step fails before the
     * catalog is saved, nothing is registered, the name stays free, and table data is unchanged. The caller opens and closes
     * {@code rows}; this method only reads it to the end.
     *
     * @param index the index definition
     * @param rows the table's live records, from a fresh scan
     * @throws StorageRuntimeException if the table or column does not exist, the name is used, the
     *     column is text too wide for the index structure (B_PLUS_TREE: over 1337 UTF-8 bytes with
     *     4096-byte pages; HASH has no such limit), or the type is not implemented yet (HASH)
     * @throws StorageInternalException if reading rows, building the index or saving the catalog
     *     fails
     */
    public synchronized void create(IndexInfo index, RecordCursor rows)
            throws StorageInternalException {
        Objects.requireNonNull(rows, "rows");
        TableSchema schema = catalog.getSchema(index.table());
        int position = schema.columnIndex(index.column());
        if (position < 0) {
            throw new StorageRuntimeException(
                    "unknown column: " + index.column() + " in table " + index.table());
        }
        if (isRegistered(index.name())) {
            throw new StorageRuntimeException("index name already used: " + index.name());
        }
        if (index.type() != IndexType.B_PLUS_TREE) {
            throw new StorageRuntimeException("index type not supported yet: " + index.type());
        }
        Column column = schema.columns().get(position);
        String file = catalog.indexFile(index.name());
        IndexPageStore store = new IndexPageStore(file, blocks, buffers);
        Index structure = open(index, store, column);
        try {
            replaceFile(file);
            structure.initialize();
            for (StoredRow row = rows.next(); row != null; row = rows.next()) {
                add(structure, row, position, column);
            }
            store.force();
            catalog.registerIndex(index);
        } catch (StorageInternalException | RuntimeException e) {
            deleteAfterFailure(file, e);
            throw e;
        }
        // shortcut: if this fails the entry stays in memory, because CatalogManager cannot remove
        // one yet, so the file stays too. Undo both once SM4 adds unregisterIndex.
        catalog.save();
    }

    /**
     * Finds the records whose key equals {@code key}, including duplicates. The key must be of the
     * column's type family; no conversion is done.
     *
     * @param index the index name
     * @param key the key, possibly NULL
     * @return the matching Rids, unordered; empty if none or {@code key} is NULL
     * @throws StorageInternalException if the index does not exist or is damaged, or {@code key}
     *     is of another type family (checked before NULL)
     */
    public synchronized List<Rid> lookup(String index, Value key) throws StorageInternalException {
        Objects.requireNonNull(key, "key");
        IndexInfo info = requireIndex(index);
        Column column = columnOf(info);
        Optional<Value> normalized = IndexKeys.normalize(key, column);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return open(info, column).lookup(normalized.get());
    }

    /**
     * Finds the records whose key lies in a range, on a B+ tree index. Non-null bounds must be of
     * the column's type family; no conversion is done.
     *
     * @param index the index name
     * @param lower the lower bound, {@code null} if unbounded
     * @param includeLower whether {@code lower} itself matches
     * @param upper the upper bound, {@code null} if unbounded
     * @param includeUpper whether {@code upper} itself matches
     * @return the matching Rids ordered by key, then Rid; empty if none, a bound is NULL, or the
     *     range is empty
     * @throws StorageInternalException if the index does not exist, is damaged or is not a B+
     *     tree, or a bound is of another type family (checked before NULL)
     */
    public synchronized List<Rid> range(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper)
            throws StorageInternalException {
        IndexInfo info = requireIndex(index);
        if (!info.type().supportsRange()) {
            throw new StorageInternalException("index does not support range search: " + index);
        }
        Column column = columnOf(info);
        boolean lowerIsNull = lower != null && IndexKeys.normalize(lower, column).isEmpty();
        boolean upperIsNull = upper != null && IndexKeys.normalize(upper, column).isEmpty();
        if (lowerIsNull || upperIsNull) {
            return List.of();
        }
        return open(info, column).range(lower, includeLower, upper, includeUpper);
    }

    /**
     * Adds a new record to every index on its table. Called by the facade after the record is
     * stored.
     *
     * <p>A NULL key is not added. Idempotent: an entry that already exists is left as is, so redo
     * may repeat the call. A table without indexes is a no-op.
     *
     * @param table the table name
     * @param rid the record's address
     * @param row the stored row, matching the table's schema
     * @param lsn the LSN of the INSERT log record
     * @throws StorageInternalException on an I/O failure
     */
    public synchronized void onInsert(String table, Rid rid, Row row, long lsn)
            throws StorageInternalException {
        Objects.requireNonNull(row, "row");
        change(table, rid, null, row, lsn);
    }

    /**
     * Moves a record's entries from its old keys to its new keys in every index on its table.
     * Called by the facade after the record is rewritten in place; {@code rid} does not change.
     *
     * <p>Per index: if the key is unchanged, nothing is done. Otherwise the old entry is removed
     * when the old key is non-NULL, and a new entry is added when the new key is non-NULL, so a
     * change from or to NULL leaves no stale or duplicate entry. Idempotent, like {@link
     * #onInsert} and {@link #onDelete}.
     *
     * @param table the table name
     * @param rid the record's address
     * @param before the row as it was stored before the update
     * @param after the row as it is stored now
     * @param lsn the LSN of the UPDATE log record
     * @throws StorageInternalException on an I/O failure
     */
    public synchronized void onUpdate(String table, Rid rid, Row before, Row after, long lsn)
            throws StorageInternalException {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        change(table, rid, before, after, lsn);
    }

    /**
     * Removes a record's entries from every index on its table, leaving other records with the
     * same key. Called by the facade after the record is deleted, and once per record by {@code
     * clearTable}.
     *
     * <p>{@code before} is only used to find the entries. Idempotent: an entry that is not there
     * is ignored, so redo may repeat the call.
     *
     * @param table the table name
     * @param rid the deleted record's address
     * @param before the row as it was stored before the delete
     * @param lsn the LSN of the DELETE log record
     * @throws StorageInternalException on an I/O failure
     */
    public synchronized void onDelete(String table, Rid rid, Row before, long lsn)
            throws StorageInternalException {
        Objects.requireNonNull(before, "before");
        change(table, rid, before, null, lsn);
    }

    /**
     * Rebuilds every index on a table from scratch, discarding the current entries. Used after an
     * FRM restore and when an index was not shut down cleanly.
     *
     * <p>The caller guarantees that nothing else writes to the table meanwhile (maintenance mode),
     * and opens and closes {@code rows}.
     *
     * @param table the table name
     * @param rows the table's live records, from a fresh scan
     * @throws StorageInternalException if reading {@code rows} fails or on an I/O failure
     */
    public synchronized void rebuild(String table, RecordCursor rows)
            throws StorageInternalException {
        Objects.requireNonNull(rows, "rows");
        List<IndexInfo> indexes = catalog.listIndexes(table);
        if (indexes.isEmpty()) {
            return;
        }
        TableSchema schema = catalog.getSchema(table);
        try {
            List<Build> builds = new ArrayList<>();
            for (IndexInfo info : indexes) {
                String file = catalog.indexFile(info.name());
                IndexPageStore store = new IndexPageStore(file, blocks, buffers);
                int position = schema.columnIndex(info.column());
                Column column = schema.columns().get(position);
                Index structure = open(info, store, column);
                replaceFile(file);
                structure.initialize();
                builds.add(new Build(store, structure, position, column));
            }
            for (StoredRow row = rows.next(); row != null; row = rows.next()) {
                for (Build build : builds) {
                    add(build.structure(), row, build.position(), build.column());
                }
            }
            for (Build build : builds) {
                build.store().force();
            }
        } catch (StorageInternalException | RuntimeException e) {
            indexes.forEach(info -> damaged.add(info.name()));
            throw e;
        }
        indexes.forEach(info -> damaged.remove(info.name()));
    }

    /**
     * Removes every index on a table. Called by the facade's {@code dropTable} before the table's
     * data and catalog entry are removed; the catalog entries of the indexes go with the table's.
     *
     * <p>Discard each index file's cached pages before deleting its file through BlockManager.
     * Table/index catalog entries are removed by the facade afterwards. A table without indexes
     * is a no-op.
     *
     * @param table the table name
     * @throws StorageInternalException on an I/O failure
     */
    public synchronized void dropIndexesOf(String table) throws StorageInternalException {
        for (IndexInfo info : catalog.listIndexes(table)) {
            String file = catalog.indexFile(info.name());
            buffers.invalidateFile(file);
            blocks.delete(file);
            damaged.remove(info.name());
        }
    }

    /** Discards index runtime state after restore, without persisting old roots or metadata. */
    public synchronized void invalidateAll() {
        damaged.clear();
    }

    /**
     * Applies one record's change to every index on its table, or to none. A key that did not
     * change needs no work. All keys are worked out before anything is written, so a row that does
     * not match the schema changes nothing.
     *
     * @param table the table name
     * @param rid the record's address
     * @param before the row before the change, {@code null} if it did not exist
     * @param after the row after the change, {@code null} if it no longer exists
     * @param lsn the LSN of the operation's log record
     * @throws StorageInternalException on a storage failure; the record's entries are then as they
     *     were, in every index that is not damaged
     */
    private void change(String table, Rid rid, Row before, Row after, long lsn)
            throws StorageInternalException {
        Objects.requireNonNull(rid, "rid");
        List<IndexInfo> indexes = catalog.listIndexes(table);
        if (indexes.isEmpty()) {
            return;
        }
        TableSchema schema = catalog.getSchema(table);
        List<KeyChange> changes = new ArrayList<>();
        for (IndexInfo info : indexes) {
            int position = schema.columnIndex(info.column());
            Column column = schema.columns().get(position);
            Value oldKey = keyOf(before, position, column);
            Value newKey = keyOf(after, position, column);
            if (!damaged.contains(info.name()) && !Objects.equals(oldKey, newKey)) {
                changes.add(new KeyChange(info.name(), open(info, column), oldKey, newKey));
            }
        }
        for (int i = 0; i < changes.size(); i++) {
            KeyChange change = changes.get(i);
            try {
                move(change.structure(), change.oldKey(), change.newKey(), rid, lsn);
            } catch (StorageInternalException | RuntimeException e) {
                // It may have stopped inside a split, where an undo cannot find everything.
                damaged.add(change.index());
                undo(changes.subList(0, i), rid, lsn, e);
                throw e;
            }
        }
    }

    /**
     * Reverses completed changes, latest first. An index that cannot be reversed is damaged.
     *
     * @param changes the changes that completed
     * @param rid the record's address
     * @param lsn the LSN of the failed operation's log record
     * @param failure the original error, which collects any failure here as suppressed
     */
    private void undo(List<KeyChange> changes, Rid rid, long lsn, Exception failure) {
        for (int i = changes.size() - 1; i >= 0; i--) {
            KeyChange change = changes.get(i);
            try {
                move(change.structure(), change.newKey(), change.oldKey(), rid, lsn);
            } catch (StorageInternalException | RuntimeException e) {
                damaged.add(change.index());
                failure.addSuppressed(e);
            }
        }
    }

    /**
     * Moves a record's entry between keys within one index. Adding comes first, so a failure never
     * leaves the record missing from an index it should be in.
     *
     * @param structure the index
     * @param from the key to remove, {@code null} for none
     * @param to the key to add, {@code null} for none
     * @param rid the record's address
     * @param lsn the LSN of the operation's log record
     * @throws StorageInternalException on a storage failure
     */
    private static void move(Index structure, Value from, Value to, Rid rid, long lsn)
            throws StorageInternalException {
        if (to != null) {
            structure.insert(to, rid, lsn);
        }
        if (from != null) {
            structure.delete(from, rid, lsn);
        }
    }

    /**
     * Returns a row's key for an index.
     *
     * @param row the row, possibly {@code null}
     * @param position the column's position
     * @param column the column
     * @return the key, or {@code null} if there is no row or the key is NULL
     * @throws StorageInternalException if the value is of another type family than the column
     */
    private static Value keyOf(Row row, int position, Column column)
            throws StorageInternalException {
        return row == null ? null : IndexKeys.normalize(row.get(position), column).orElse(null);
    }

    /**
     * Adds a scanned row to an index being built.
     *
     * @param structure the index
     * @param row the row
     * @param position the column's position
     * @param column the column
     * @throws StorageInternalException on a storage failure
     */
    private static void add(Index structure, StoredRow row, int position, Column column)
            throws StorageInternalException {
        Value key = keyOf(row.row(), position, column);
        if (key != null) {
            structure.insertUnlogged(key, row.rid());
        }
    }

    /**
     * Returns whether a name is registered. The catalog reports an unknown name by throwing.
     *
     * @param name the index name
     * @return {@code true} if an index has this name
     */
    private boolean isRegistered(String name) {
        try {
            catalog.getIndex(name);
            return true;
        } catch (StorageRuntimeException e) {
            return false;
        }
    }

    /**
     * Returns a searchable index's definition.
     *
     * @param index the index name
     * @return the definition
     * @throws StorageInternalException if the name is unknown or the index is damaged
     */
    private IndexInfo requireIndex(String index) throws StorageInternalException {
        Objects.requireNonNull(index, "index");
        IndexInfo info;
        try {
            info = catalog.getIndex(index);
        } catch (StorageRuntimeException e) {
            throw new StorageInternalException("unknown index: " + index, e);
        }
        if (damaged.contains(index)) {
            throw new StorageInternalException("index needs a rebuild: " + index);
        }
        return info;
    }

    /**
     * Returns the indexed column.
     *
     * @param info the index
     * @return the column
     */
    private Column columnOf(IndexInfo info) {
        TableSchema schema = catalog.getSchema(info.table());
        return schema.columns().get(schema.columnIndex(info.column()));
    }

    /**
     * Opens an index on its catalog-assigned file. Opening reads nothing.
     *
     * @param info the index
     * @param column the indexed column
     * @return the structure
     * @throws StorageInternalException if the structure is not implemented
     */
    private Index open(IndexInfo info, Column column) throws StorageInternalException {
        return open(
                info, new IndexPageStore(catalog.indexFile(info.name()), blocks, buffers), column);
    }

    /**
     * Picks the structure for an index's type.
     *
     * @param info the index
     * @param store the index's pages
     * @param column the indexed column
     * @return the structure
     * @throws StorageInternalException if the structure is not implemented
     */
    private static Index open(IndexInfo info, IndexPageStore store, Column column)
            throws StorageInternalException {
        return switch (info.type()) {
            case B_PLUS_TREE -> new BPlusTree(store, column);
            case HASH ->
                    throw new StorageInternalException(
                            "index type not supported yet: " + info.type());
        };
    }

    /**
     * Makes a file empty, whatever was there: an earlier version of the index, or what a crash left
     * during a build.
     *
     * @param file the file
     * @throws StorageInternalException on an I/O failure
     */
    private void replaceFile(String file) throws StorageInternalException {
        buffers.invalidateFile(file);
        blocks.delete(file);
        blocks.create(file);
    }

    /**
     * Removes a failed build's file.
     *
     * @param file the file
     * @param failure the build's error, which collects any failure here as suppressed
     */
    private void deleteAfterFailure(String file, Exception failure) {
        try {
            buffers.invalidateFile(file);
            blocks.delete(file);
        } catch (StorageInternalException | RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    /**
     * One index being rebuilt.
     *
     * @param store the index's pages
     * @param structure the index
     * @param position the column's position
     * @param column the indexed column
     */
    private record Build(IndexPageStore store, Index structure, int position, Column column) {}

    /**
     * What one record's change means for one index.
     *
     * @param index the index name
     * @param structure the index
     * @param oldKey the key to remove, {@code null} for none
     * @param newKey the key to add, {@code null} for none
     */
    private record KeyChange(String index, Index structure, Value oldKey, Value newKey) {}
}
