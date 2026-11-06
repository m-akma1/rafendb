package org.rafendb.storage.catalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import org.rafendb.api.storage.RecordCursor;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.ReferencingForeignKey;
import org.rafendb.common.schema.ReferentialAction;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

public class CatalogManager {
    protected final Path directory;

    private static final String SNAPSHOT_FILE = "catalog.snapshot";
    private static final String SNAPSHOT_TMP = "catalog.snapshot.tmp";
    private static final String ALLOC_FILE = "alloc.hw";
    private static final String ALLOC_TMP = "alloc.hw.tmp";

    private static final int SNAPSHOT_MAGIC = 0x52414643; // "RAFC"
    private static final int SNAPSHOT_VERSION = 1;
    private static final int ALLOC_MAGIC = 0x52414641; // "RAFA"
    private static final int ALLOC_VERSION = 1;

    // In-memory state
    private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
    private final Map<String, String> tableFiles = new LinkedHashMap<>();
    private final Map<String, TableStats> stats = new HashMap<>();
    private final Map<String, IndexInfo> indexesByName = new LinkedHashMap<>();
    private final Map<String, String> indexFiles = new LinkedHashMap<>();
    private final Map<String, Long> allocHighWater = new HashMap<>(); // key = table + '\0' + block
    private boolean loaded = false;

    /** Wires the database directory without loading or creating the catalog. */
    public CatalogManager(Path directory) {
        this.directory =
                Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    // Exception Mapping

    /**
     * Client errors (unknown table/column, name clash, schema mismatch) surface as {@link
     * StorageRuntimeException}; storage failures surface as {@link StorageInternalException}.
     */
    private static StorageRuntimeException clientError(String message) {
        return new StorageRuntimeException(message);
    }

    private static StorageRuntimeException clientError(String message, Throwable cause) {
        return new StorageRuntimeException(message, cause);
    }

    // Lifecycle

    /** Loads the binary catalog and allocation metadata; never hides a corrupt existing catalog. */
    public synchronized void load() throws StorageInternalException {
        if (loaded) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw wrap("failed to create catalog directory", e);
        }

        Path snap = directory.resolve(SNAPSHOT_FILE);
        Path alloc = directory.resolve(ALLOC_FILE);

        boolean snapExists = Files.exists(snap);
        boolean allocExists = Files.exists(alloc);

        if (!snapExists && !allocExists) {
            // New database: nothing to load, allocation starts empty.
            loaded = true;
            return;
        }
        if (snapExists != allocExists) {
            // One half present => corrupt/incomplete. Fail loudly, do not hide.
            throw new StorageInternalException(
                    "catalog inconsistent: snapshot=" + snapExists + ", alloc=" + allocExists);
        }

        Map<String, TableSchema> newSchemas = new LinkedHashMap<>();
        Map<String, String> newTableFiles = new LinkedHashMap<>();
        Map<String, TableStats> newStats = new HashMap<>();
        Map<String, IndexInfo> newIndexes = new LinkedHashMap<>();
        Map<String, String> newIndexFiles = new LinkedHashMap<>();
        Map<String, Long> newAlloc = new HashMap<>();

        readSnapshot(snap, newSchemas, newTableFiles, newStats, newIndexes, newIndexFiles);
        readAlloc(alloc, newAlloc);

        // Prune allocation entries whose table is not in the snapshot. A DROP TABLE that failed
        // between metadata update and allocation-file write can leave these behind; they are
        // harmless but never read again. Removing them keeps the two files consistent.
        newAlloc.keySet().removeIf(k -> {
            int nul = k.indexOf('\u0000');
            String t = nul < 0 ? k : k.substring(0, nul);
            return !newSchemas.containsKey(t);
        });

        // Commit to in-memory state only after full successful parse.
        schemas.clear();
        schemas.putAll(newSchemas);
        tableFiles.clear();
        tableFiles.putAll(newTableFiles);
        stats.clear();
        stats.putAll(newStats);
        indexesByName.clear();
        indexesByName.putAll(newIndexes);
        indexFiles.clear();
        indexFiles.putAll(newIndexFiles);
        allocHighWater.clear();
        allocHighWater.putAll(newAlloc);
        loaded = true;
    }

    /** Durably saves schema/statistics; never overwrites or rewinds the allocation high-water file. */
    public synchronized void save() throws StorageInternalException {
    ensureLoaded();
    byte[] bytes = encodeSnapshot();
    try {
        atomicWrite(directory.resolve(SNAPSHOT_TMP), directory.resolve(SNAPSHOT_FILE), bytes);
    } catch (IOException e) {
        throw wrap("failed to save catalog snapshot", e);
    }
    // Allocation file is intentionally untouched here.
}
    // Allocation high-water

    /**
     * Returns the first slot never allocated in this table/block, from the durable allocation
     * high-water file. A missing block starts at zero; cancellation/deletion never lowers it.
     */
    public synchronized int nextSlot(String table, long block) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        if (block < 0) {
            throw new StorageRuntimeException("block must be >= 0, got " + block);
        }
        Long hw = allocHighWater.get(allocKey(table, block));
        if (hw == null) {
            return 0;
        }
        if (hw > Integer.MAX_VALUE) {
            // Slot numbers are int; a high-water beyond int range cannot be represented.
            throw new StorageRuntimeException(
                    "allocation high-water overflow for table=" + table + " block=" + block);
        }
        return hw.intValue();
    }

    /**
     * Durably advances nextSlot past rid before reserveRid returns or applyRecovery changes a slot.
     * Monotonic/idempotent; force an atomic replacement of a separate binary allocation file,
     * including its directory entry. Does not flush data pages or require/generate a WAL LSN.
     * On failure no Rid is exposed; retain the in-memory high-water to avoid retrying that
     * candidate. Every successfully exposed allocation remains consumed after restart.
     */
    public synchronized void persistAllocation(String table, Rid rid)
            throws StorageInternalException {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(rid, "rid");

        String key = allocKey(table, rid.block());
        long candidate = (long) rid.slot() + 1L;
        Long current = allocHighWater.get(key);
        if (current != null && current >= candidate) {
            // Idempotent: never lower the high-water mark.
            return;
        }

        // Update in-memory first; on IO failure we keep it so this candidate is not re-tried
        // below the persisted level, matching the documented contract.
        allocHighWater.put(key, candidate);

        try {
            byte[] bytes = encodeAlloc();
            atomicWrite(directory.resolve(ALLOC_TMP), directory.resolve(ALLOC_FILE), bytes);
        } catch (IOException e) {
            // In-memory state is retained; the rid must not be published by the caller.
            throw wrap("failed to persist allocation for " + table + " " + rid, e);
        }
    }

    // Schema / files

    /** Returns sorted table names. */
    public synchronized List<String> listTables() {
        ensureLoaded();
        List<String> names = new ArrayList<>(schemas.keySet());
        Collections.sort(names);
        return Collections.unmodifiableList(names);
    }

    /** Returns the schema including outgoing foreign keys; rejects an unknown table. */
    public synchronized TableSchema getSchema(String table) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        TableSchema s = schemas.get(table);
        if (s == null) {
            throw clientError("unknown table: " + table);
        }
        return s;
    }

    /**
     * Maps a normalized table name to its canonical physical file, including before registration.
     * Does not create/register the table; callers use getSchema when existence must be checked.
     */
    public synchronized String tableFile(String table) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        String existing = tableFiles.get(table);
        if (existing != null) {
            return existing;
        }
        return safeRelativeFile("tbl_" + hex(table) + ".dat");
    }

    /** Validates a prospective schema and FK targets before the facade creates its file. */
    public synchronized void validateTable(TableSchema schema) {
        ensureLoaded();
        Objects.requireNonNull(schema, "schema");
        if (schemas.containsKey(schema.table())) {
            throw clientError("table already exists: " + schema.table());
        }
        validateSchemaBody(schema, schemas);
    }

    /** Validates and registers a schema only after the facade has prepared its empty file. */
    public synchronized void registerTable(TableSchema schema) {
        ensureLoaded();
        Objects.requireNonNull(schema, "schema");
        if (schemas.containsKey(schema.table())) {
            throw clientError("table already exists: " + schema.table());
        }
        validateSchemaBody(schema, schemas);
        schemas.put(schema.table(), schema);
        tableFiles.put(schema.table(), safeRelativeFile("tbl_" + hex(schema.table()) + ".dat"));
        stats.put(schema.table(), new TableStats(0L, 0L, 0.0, 0.0, zeroDistinct(schema)));
    }

    /**
     * Validates a row against the schema. Width, NULL policy, type family and UTF-8 byte length
     * are all delegated to {@link Column#accepts(Value)} so the rules live in one place.
     */
    public synchronized void validateRow(String table, Row row) {
        ensureLoaded();
        Objects.requireNonNull(row, "row");
        TableSchema schema = getSchema(table);
        List<Column> cols = schema.columns();
        if (row.size() != cols.size()) {
            throw clientError(
                    "row width mismatch: expected " + cols.size() + ", got " + row.size());
        }
        for (int i = 0; i < cols.size(); i++) {
            Column col = cols.get(i);
            Value v = row.get(i);
            if (v == null) {
                // Row record already rejects null elements; defensive only.
                throw clientError("null Value at " + table + "." + col.name());
            }
            if (!col.accepts(v)) {
                throw clientError(
                        "value "
                                + v
                                + " is not acceptable for "
                                + table
                                + "."
                                + col.name()
                                + " ("
                                + col.type()
                                + (col.type().isText() ? "(" + col.length() + ")" : "")
                                + (col.nullable() ? ", nullable" : ", not null")
                                + ")");
            }
        }
    }

    // Foreign keys

    /** Lists incoming foreign keys from all tables, including the table itself. */
    public synchronized List<ReferencingForeignKey> listReferencingForeignKeys(String table) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        if (!schemas.containsKey(table)) {
            throw clientError("unknown table: " + table);
        }
        List<ReferencingForeignKey> out = new ArrayList<>();
        for (TableSchema s : schemas.values()) {
            for (ForeignKey fk : s.foreignKeys()) {
                if (table.equals(fk.referencedTable())) {
                    out.add(new ReferencingForeignKey(s.table(), fk));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Checks DROP RESTRICT/CASCADE before any file, index or catalog mutation. */
    public synchronized void validateDrop(String table, boolean cascade) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        if (!schemas.containsKey(table)) {
            throw clientError("unknown table: " + table);
        }
        if (cascade) {
            return;
        }
        for (ReferencingForeignKey r : listReferencingForeignKeys(table)) {
            if (!r.referencingTable().equals(table)) {
                throw clientError(
                        "cannot DROP "
                                + table
                                + " RESTRICT: referenced by "
                                + r.referencingTable()
                                + "."
                                + r.foreignKey().name());
            }
        }
    }

    /** Removes table/index metadata and, for CASCADE, incoming FK definitions, preserving children. */
    public synchronized void dropTable(String table, boolean cascade)
            throws StorageInternalException {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        if (!schemas.containsKey(table)) {
            throw clientError("unknown table: " + table);
        }
        if (cascade) {
            // Iterate over a snapshot to allow schemas.put inside the loop.
            for (Map.Entry<String, TableSchema> e : new ArrayList<>(schemas.entrySet())) {
                if (e.getKey().equals(table)) {
                    continue;
                }
                TableSchema child = e.getValue();
                List<ForeignKey> kept = new ArrayList<>();
                boolean changed = false;
                for (ForeignKey fk : child.foreignKeys()) {
                    if (table.equals(fk.referencedTable())) {
                        changed = true;
                    } else {
                        kept.add(fk);
                    }
                }
                if (changed) {
                    schemas.put(e.getKey(), new TableSchema(child.table(), child.columns(), kept));
                }
            }
        }

        // Delete the physical table file before removing its catalog mapping.
        // This ensures DROP TABLE does not leave stale rows behind when the same
        // table name is created again.
        String tableFile = tableFiles.get(table);
        if (tableFile != null) {
            try {
                Files.deleteIfExists(directory.resolve(tableFile));
            } catch (IOException e) {
                throw wrap("failed to delete table file for " + table, e);
            }
        }

        schemas.remove(table);
        tableFiles.remove(table);
        stats.remove(table);

        List<String> toRemove = new ArrayList<>();
        for (IndexInfo idx : indexesByName.values()) {
            if (table.equals(idx.table())) {
                toRemove.add(idx.name());
            }
        }
        for (String name : toRemove) {
            indexesByName.remove(name);
            indexFiles.remove(name);
        }

        // DROP TABLE removes the table identity entirely: reset its allocation high-water so a
        // subsequent CREATE TABLE with the same name starts at slot 0, matching the fresh file.
        boolean allocChanged =
                allocHighWater.keySet().removeIf(k -> k.startsWith(table + "\u0000"));
        if (allocChanged) {
            try {
                byte[] bytes = encodeAlloc();
                atomicWrite(directory.resolve(ALLOC_TMP), directory.resolve(ALLOC_FILE), bytes);
            } catch (IOException e) {
                // The in-memory catalog is already updated; the caller must treat this DROP as
                // failed. The stale alloc entries are pruned by load() on the next restart.
                throw wrap("failed to persist allocation reset for dropped table " + table, e);
            }
        }
    }

    // Statistics

    /** Returns cached statistics; the facade refreshes derived fields when necessary. */
    public synchronized TableStats getStats(String table) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        TableStats s = stats.get(table);
        if (s == null) {
            throw clientError("unknown table: " + table);
        }
        return s;
    }

    /** Recalculates statistics from a one-pass cursor; the caller owns and closes it. */
    public void refreshStats(String table, RecordCursor rows) throws StorageInternalException {
        Objects.requireNonNull(rows, "rows");
        TableSchema schema;
        synchronized (this) {
            ensureLoaded();
            schema = getSchema(table);
        }

        long count = 0;
        long maxBlock = -1;
        long totalBytes = 0;
        Map<String, Set<Object>> distinct = new HashMap<>();
        for (Column c : schema.columns()) {
            distinct.put(c.name(), new LinkedHashSet<>());
        }

        try {
            StoredRow r;
            while ((r = rows.next()) != null) {
                count++;
                if (r.rid().block() > maxBlock) {
                    maxBlock = r.rid().block();
                }
                Row row = r.row();
                totalBytes += estimateSize(row);
                for (int i = 0; i < row.size() && i < schema.columns().size(); i++) {
                    Column c = schema.columns().get(i);
                    distinct.get(c.name()).add(distinctKey(row.get(i)));
                }
            }
        } catch (StorageInternalException e) {
            throw new StorageInternalException("refreshStats failed for " + table, e);
        }

        long blocks = maxBlock < 0 ? 0 : maxBlock + 1;
        double avgSize = count == 0 ? 0.0 : (double) totalBytes / count;
        double blocking = avgSize == 0.0 ? 0.0 : Math.max(1.0, 4096.0 / avgSize);

        Map<String, Long> dv = new HashMap<>();
        for (Map.Entry<String, Set<Object>> e : distinct.entrySet()) {
            dv.put(e.getKey(), (long) e.getValue().size());
        }

        TableStats computed = new TableStats(count, blocks, avgSize, blocking, dv);
        synchronized (this) {
            stats.put(table, computed);
        }
    }

    /** Applies a live-row count delta/current block count and invalidates derived statistics. */
    public synchronized void recordChanged(String table, long rowDelta, long blockCount) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        TableStats old = stats.get(table);
        if (old == null) {
            throw clientError("unknown table: " + table);
        }
        long newCount = old.recordCount() + rowDelta;
        if (newCount < 0) {
            newCount = 0; // recovery double-apply safety: never go negative
        }
        long newBlocks = Math.max(0, blockCount);
        stats.put(
                table,
                new TableStats(
                        newCount,
                        newBlocks,
                        old.averageRecordSize(),
                        old.blockingFactor(),
                        old.distinctValues()));
    }

    // Indexes

    /** Returns registered indexes on a table. */
    public synchronized List<IndexInfo> listIndexes(String table) {
        ensureLoaded();
        Objects.requireNonNull(table, "table");
        if (!schemas.containsKey(table)) {
            throw clientError("unknown table: " + table);
        }
        List<IndexInfo> out = new ArrayList<>();
        for (IndexInfo idx : indexesByName.values()) {
            if (table.equals(idx.table())) {
                out.add(idx);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Returns one registered index; IndexManager maps unknown names to its public error contract. */
    public synchronized IndexInfo getIndex(String name) {
        ensureLoaded();
        Objects.requireNonNull(name, "name");
        IndexInfo idx = indexesByName.get(name);
        if (idx == null) {
            throw clientError("unknown index: " + name);
        }
        return idx;
    }

    /** Maps an index name to its canonical file, also usable before registerIndex during a build. */
    public synchronized String indexFile(String name) {
        ensureLoaded();
        Objects.requireNonNull(name, "name");
        String existing = indexFiles.get(name);
        if (existing != null) {
            return existing;
        }
        return safeRelativeFile("idx_" + hex(name) + ".idx");
    }

    /** Registers an index as the final successful step of IndexManager.create. */
    public synchronized void registerIndex(IndexInfo index) {
        ensureLoaded();
        Objects.requireNonNull(index, "index");
        if (indexesByName.containsKey(index.name())) {
            throw clientError("index already exists: " + index.name());
        }
        TableSchema schema = schemas.get(index.table());
        if (schema == null) {
            throw clientError("unknown table for index: " + index.table());
        }
        if (schema.columnIndex(index.column()) < 0) {
            throw clientError(
                    "unknown column for index: " + index.table() + "." + index.column());
        }
        indexesByName.put(index.name(), index);
        indexFiles.put(index.name(), safeRelativeFile("idx_" + hex(index.name()) + ".idx"));
    }

    // Validation helpers

    /**
     * Checks every outgoing FK: referenced table exists (or is this table), referenced columns are
     * exactly the target's primary key in order, and each column pair shares a type family.
     */
    private void validateSchemaBody(TableSchema schema, Map<String, TableSchema> existing) {
        for (ForeignKey fk : schema.foreignKeys()) {
            if (fk.columns().isEmpty()) {
                throw clientError("foreign key " + fk.name() + " has no columns");
            }
            TableSchema target;
            if (fk.referencedTable().equals(schema.table())) {
                target = schema;
            } else {
                target = existing.get(fk.referencedTable());
                if (target == null) {
                    throw clientError(
                            "foreign key "
                                    + fk.name()
                                    + " references unknown table: "
                                    + fk.referencedTable());
                }
            }
            if (fk.referencedColumns().isEmpty()) {
                throw clientError("foreign key " + fk.name() + " has no referenced columns");
            }
            if (fk.columns().size() != fk.referencedColumns().size()) {
                throw clientError("foreign key " + fk.name() + " column count mismatch");
            }

            // Contract: referenced columns are exactly the target's primary key, in order.
            List<Column> targetPk = target.primaryKeyColumns();
            if (targetPk.size() != fk.referencedColumns().size()) {
                throw clientError(
                        "foreign key "
                                + fk.name()
                                + " referenced columns are not the primary key of "
                                + fk.referencedTable());
            }
            for (int i = 0; i < targetPk.size(); i++) {
                if (!targetPk.get(i).name().equals(fk.referencedColumns().get(i))) {
                    throw clientError(
                            "foreign key "
                                    + fk.name()
                                    + " referenced column "
                                    + fk.referencedColumns().get(i)
                                    + " is not primary key column "
                                    + targetPk.get(i).name()
                                    + " of "
                                    + fk.referencedTable());
                }
            }

            // Each (child column, parent column) pair must share a type family.
            for (int i = 0; i < fk.columns().size(); i++) {
                String childName = fk.columns().get(i);
                Column childCol =
                        schema.column(childName)
                                .orElseThrow(
                                        () ->
                                                clientError(
                                                        "foreign key "
                                                                + fk.name()
                                                                + " names unknown column: "
                                                                + childName));
                Column parentCol = targetPk.get(i);
                if (family(childCol.type()) != family(parentCol.type())) {
                    throw clientError(
                            "foreign key "
                                    + fk.name()
                                    + " type mismatch: "
                                    + childCol.name()
                                    + " ("
                                    + childCol.type()
                                    + ") vs "
                                    + parentCol.name()
                                    + " ("
                                    + parentCol.type()
                                    + ")");
                }
            }
        }
    }

    /** CHAR and VARCHAR share one family, matching {@link Value#equals}. */
    private static DataType family(DataType t) {
        return t.isText() ? DataType.CHAR : t;
    }

    /** Estimates stored bytes for a row from the declared column types and payloads. */
    private static long estimateSize(Row row) {
        long n = 0;
        for (int i = 0; i < row.size(); i++) {
            n += estimateSize(row.get(i));
        }
        return n;
    }

    /**
     * Estimates the on-disk width of one value: fixed width for numerics, UTF-8 byte length for
     * text, zero for NULL (NULL is encoded in the null bitmap, not as payload).
     */
    private static long estimateSize(Value v) {
        if (v == null || v.isNull()) {
            return 0L;
        }
        return switch (v.type()) {
            case INTEGER -> Integer.BYTES;
            case FLOAT -> Float.BYTES;
            case CHAR, VARCHAR -> v.asText().getBytes(StandardCharsets.UTF_8).length;
        };
    }

    /**
     * Key for distinct-value counting. {@link Value#equals} already collapses CHAR/VARCHAR into
     * one family and normalizes -0.0f to 0.0f, so the Value itself is the right key. SQL NULL is
     * one distinct value in QO terms, regardless of declared type.
     */
    private static Object distinctKey(Value v) {
        return v == null || v.isNull() ? NULL_MARKER : v;
    }

    private static Map<String, Long> zeroDistinct(TableSchema schema) {
        Map<String, Long> m = new HashMap<>();
        for (Column c : schema.columns()) {
            m.put(c.name(), 0L);
        }
        return m;
    }

    // Encoding / decoding

    private byte[] encodeSnapshot() throws StorageInternalException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(SNAPSHOT_MAGIC);
            out.writeInt(SNAPSHOT_VERSION);

            out.writeInt(schemas.size());
            for (TableSchema s : schemas.values()) {
                writeString(out, s.table());

                out.writeInt(s.columns().size());
                for (Column c : s.columns()) {
                    writeColumn(out, c);
                }

                out.writeInt(s.foreignKeys().size());
                for (ForeignKey fk : s.foreignKeys()) {
                    writeForeignKey(out, fk);
                }

                writeString(out, tableFiles.get(s.table()));

                TableStats st = stats.get(s.table());
                writeStats(out, st);
            }

            out.writeInt(indexesByName.size());
            for (IndexInfo idx : indexesByName.values()) {
                writeString(out, idx.name());
                writeString(out, idx.table());
                writeString(out, idx.column());
                writeString(out, idx.type().name());
                writeString(out, indexFiles.get(idx.name()));
            }

            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw wrap("failed to encode catalog snapshot", e);
        }
    }

    private void readSnapshot(
            Path file,
            Map<String, TableSchema> outSchemas,
            Map<String, String> outTableFiles,
            Map<String, TableStats> outStats,
            Map<String, IndexInfo> outIndexes,
            Map<String, String> outIndexFiles)
            throws StorageInternalException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw wrap("failed to read catalog snapshot", e);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != SNAPSHOT_MAGIC) {
                throw new IOException("bad snapshot magic: " + Integer.toHexString(magic));
            }
            int version = in.readInt();
            if (version != SNAPSHOT_VERSION) {
                throw new IOException("unsupported snapshot version: " + version);
            }
            int tableCount = in.readInt();
            if (tableCount < 0) {
                throw new IOException("negative table count");
            }
            for (int t = 0; t < tableCount; t++) {
                String table = readString(in);
                int colCount = in.readInt();
                if (colCount <= 0) {
                    throw new IOException("invalid column count for table " + table);
                }
                List<Column> cols = new ArrayList<>(colCount);
                for (int i = 0; i < colCount; i++) {
                    cols.add(readColumn(in));
                }
                int fkCount = in.readInt();
                if (fkCount < 0) {
                    throw new IOException("negative fk count for table " + table);
                }
                List<ForeignKey> fks = new ArrayList<>(fkCount);
                for (int i = 0; i < fkCount; i++) {
                    fks.add(readForeignKey(in));
                }
                String tfile = readString(in);
                TableStats st = readStats(in);
                TableSchema schema = new TableSchema(table, cols, fks);
                outSchemas.put(table, schema);
                outTableFiles.put(table, tfile);
                outStats.put(table, st);
            }
            int idxCount = in.readInt();
            if (idxCount < 0) {
                throw new IOException("negative index count");
            }
            for (int i = 0; i < idxCount; i++) {
                String name = readString(in);
                String table = readString(in);
                String column = readString(in);
                String typeName = readString(in);
                String ifile = readString(in);
                IndexInfo idx = new IndexInfo(name, table, column, IndexType.valueOf(typeName));
                outIndexes.put(name, idx);
                outIndexFiles.put(name, ifile);
            }
        } catch (IOException | RuntimeException e) {
            throw wrap("corrupt catalog snapshot at " + file, e);
        }
    }

    private byte[] encodeAlloc() throws StorageInternalException {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(ALLOC_MAGIC);
            out.writeInt(ALLOC_VERSION);
            // Sort for deterministic output.
            TreeMap<String, Long> sorted = new TreeMap<>(allocHighWater);
            out.writeInt(sorted.size());
            for (Map.Entry<String, Long> e : sorted.entrySet()) {
                String[] parts = e.getKey().split("\u0000", 2);
                writeString(out, parts[0]);
                out.writeLong(Long.parseLong(parts[1]));
                out.writeLong(e.getValue());
            }
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw wrap("failed to encode allocation file", e);
        }
    }

    private void readAlloc(Path file, Map<String, Long> out) throws StorageInternalException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            throw wrap("failed to read allocation file", e);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = in.readInt();
            if (magic != ALLOC_MAGIC) {
                throw new IOException("bad alloc magic: " + Integer.toHexString(magic));
            }
            int version = in.readInt();
            if (version != ALLOC_VERSION) {
                throw new IOException("unsupported alloc version: " + version);
            }
            int n = in.readInt();
            if (n < 0) {
                throw new IOException("negative alloc entry count");
            }
            for (int i = 0; i < n; i++) {
                String table = readString(in);
                long block = in.readLong();
                long hw = in.readLong();
                if (block < 0 || hw < 0) {
                    throw new IOException("negative allocation entry");
                }
                out.put(allocKey(table, block), hw);
            }
        } catch (EOFException e) {
            throw wrap("truncated allocation file at " + file, e);
        } catch (IOException | RuntimeException e) {
            throw wrap("corrupt allocation file at " + file, e);
        }
    }

    private static void writeColumn(DataOutputStream out, Column c) throws IOException {
        writeString(out, c.name());
        writeString(out, c.type().name());
        out.writeInt(c.length());
        out.writeBoolean(c.nullable());
        out.writeBoolean(c.primaryKey());
    }

    private static Column readColumn(DataInputStream in) throws IOException {
        String name = readString(in);
        DataType type = DataType.valueOf(readString(in));
        int length = in.readInt();
        boolean nullable = in.readBoolean();
        boolean pk = in.readBoolean();
        return new Column(name, type, length, nullable, pk);
    }

    private static void writeForeignKey(DataOutputStream out, ForeignKey fk) throws IOException {
        writeString(out, fk.name());
        out.writeInt(fk.columns().size());
        for (String c : fk.columns()) {
            writeString(out, c);
        }
        writeString(out, fk.referencedTable());
        out.writeInt(fk.referencedColumns().size());
        for (String c : fk.referencedColumns()) {
            writeString(out, c);
        }
        writeString(out, fk.onDelete().name());
    }

    private static ForeignKey readForeignKey(DataInputStream in) throws IOException {
        String name = readString(in);
        int n = in.readInt();
        if (n < 0) {
            throw new IOException("negative fk column count");
        }
        List<String> cols = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            cols.add(readString(in));
        }
        String refTable = readString(in);
        int rn = in.readInt();
        if (rn < 0) {
            throw new IOException("negative fk referenced column count");
        }
        List<String> refCols = new ArrayList<>(rn);
        for (int i = 0; i < rn; i++) {
            refCols.add(readString(in));
        }
        ReferentialAction onDelete = ReferentialAction.valueOf(readString(in));
        return new ForeignKey(name, cols, refTable, refCols, onDelete);
    }

    private static void writeStats(DataOutputStream out, TableStats st) throws IOException {
        out.writeLong(st.recordCount());
        out.writeLong(st.blockCount());
        out.writeDouble(st.averageRecordSize());
        out.writeDouble(st.blockingFactor());
        Map<String, Long> dv = st.distinctValues();
        out.writeInt(dv.size());
        TreeMap<String, Long> sorted = new TreeMap<>(dv);
        for (Map.Entry<String, Long> e : sorted.entrySet()) {
            writeString(out, e.getKey());
            out.writeLong(e.getValue());
        }
    }

    private static TableStats readStats(DataInputStream in) throws IOException {
        long rc = in.readLong();
        long bc = in.readLong();
        double avg = in.readDouble();
        double bf = in.readDouble();
        int n = in.readInt();
        if (n < 0) {
            throw new IOException("negative distinct-value count");
        }
        Map<String, Long> dv = new HashMap<>();
        for (int i = 0; i < n; i++) {
            String k = readString(in);
            long v = in.readLong();
            dv.put(k, v);
        }
        return new TableStats(rc, bc, avg, bf, dv);
    }

    // IO helpers

    private static void atomicWrite(Path tmp, Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        try (FileChannel ch =
                FileChannel.open(
                        tmp,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        }
        try {
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        // Force directory entry on best-effort basis.
        try (FileChannel dir = FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException ignored) {
            // Not all platforms support forcing a directory; the data file was forced.
        }
    }

    private static StorageInternalException wrap(String msg, Throwable cause) {
        return new StorageInternalException(msg, cause);
    }

    private synchronized void ensureLoaded() {
        if (!loaded) {
            throw new IllegalStateException("catalog not loaded; call load() first");
        }
    }

    private static String allocKey(String table, long block) {
        return table + "\u0000" + block;
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static String safeRelativeFile(String fileName) {
        // Keep it a single relative path component; no separators, no traversal.
        Path p = Path.of(fileName);
        if (p.isAbsolute() || p.getNameCount() != 1) {
            throw new IllegalStateException("unsafe catalog file name: " + fileName);
        }
        return fileName;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 1 << 20) {
            throw new IOException("invalid string length: " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static final Object NULL_MARKER = new Object();
}
