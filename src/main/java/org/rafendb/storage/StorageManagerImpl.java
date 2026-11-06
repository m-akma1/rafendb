package org.rafendb.storage;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.ReferencingForeignKey;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;
import org.rafendb.storage.buffer.BufferManager;
import org.rafendb.storage.catalog.CatalogManager;
import org.rafendb.storage.file.BlockId;
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.IndexManager;
import org.rafendb.storage.record.RecordSerializer;
import org.rafendb.storage.record.SlottedPage;
import org.rafendb.storage.record.TableScan;
import org.rafendb.storage.seed.SampleDatabaseLoader;

/**
 * SM1 composition facade. Module dependencies and delegations are wired;
 * physical operations
 * marked TODO intentionally fail until their owners implement them. Facade
 * calls are serialized;
 * cursors release page latches before returning rows. DDL must still exclude
 * active cursors.
 */
public class StorageManagerImpl implements StorageManager {
    protected final CatalogManager catalog;
    protected final RecordSerializer serializer;
    protected final BlockManager blocks;
    protected final BufferManager buffers;
    protected final IndexManager indexes;
    protected final SampleDatabaseLoader samples;

    private enum ClosePhase {
        OPEN,
        BUFFER,
        INDEX_MARKER,
        FILES,
        CLOSED
    }

    private ClosePhase closePhase = ClosePhase.OPEN;
    private final Map<String, Integer> openCursors = new HashMap<>();

    /** Accepts one shared module graph; constructors perform no database I/O. */
    public StorageManagerImpl(
            CatalogManager catalog,
            RecordSerializer serializer,
            BlockManager blocks,
            BufferManager buffers,
            IndexManager indexes,
            SampleDatabaseLoader samples) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.blocks = Objects.requireNonNull(blocks, "blocks");
        this.buffers = Objects.requireNonNull(buffers, "buffers");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.samples = Objects.requireNonNull(samples, "samples");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<String> listTables() {
        ensureOpen();
        return catalog.listTables();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized TableSchema getSchema(String table) {
        ensureOpen();
        return catalog.getSchema(table);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<ReferencingForeignKey> listReferencingForeignKeys(String table) {
        ensureOpen();
        return catalog.listReferencingForeignKeys(table);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void createTable(TableSchema schema) throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(schema, "schema");
        catalog.validateTable(schema);
        String file = catalog.tableFile(schema.table());
        blocks.create(file);
        try {
            catalog.registerTable(schema);
            try {
                catalog.save();
            } catch (StorageInternalException | RuntimeException e) {
                catalog.dropTable(schema.table(), true);
                throw e;
            }
        } catch (StorageInternalException | RuntimeException e) {
            blocks.delete(file);
            throw e;
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void dropTable(String table, boolean cascade)
            throws StorageInternalException {
        ensureOpen();
        catalog.validateDrop(table, cascade);
        requireNoOpenCursors(table);
        String file = catalog.tableFile(table);
        indexes.dropIndexesOf(table);
        buffers.invalidateFile(file);
        catalog.dropTable(table, cascade);
        catalog.save();
        blocks.delete(file);
    }

    /**
     * {@inheritDoc} Uses the existing cursor directly, avoiding an Iterable<Row>
     * adapter.
     */
    @Override
    public synchronized TableStats getStats(String table) throws StorageInternalException {
        ensureOpen();
        try (RecordCursor rows = scan(table)) {
            catalog.refreshStats(table, rows);
        }
        return catalog.getStats(table);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<IndexInfo> listIndexes(String table) {
        ensureOpen();
        return catalog.listIndexes(table);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized RecordCursor scan(String table) throws StorageInternalException {
        ensureOpen();
        TableSchema schema = catalog.getSchema(table);
        RecordCursor cursor =
                new TableScan(catalog.tableFile(table), schema, blocks, buffers, serializer);
        openCursors.merge(table, 1, Integer::sum);
        return new TrackedCursor(table, cursor);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<StoredRow> fetch(String table, Rid rid)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(rid, "rid");
        TableSchema schema = catalog.getSchema(table);
        String file = catalog.tableFile(table);
        if (rid.block() >= blocks.size(file)) {
            return Optional.empty();
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer page = buffers.pin(block);
        try {
            Optional<byte[]> bytes = new SlottedPage(page).read(rid.slot());
            if (bytes.isEmpty()) {
                return Optional.empty();
            }
            Row row = serializer.deserialize(ByteBuffer.wrap(bytes.get()), schema);
            return Optional.of(new StoredRow(rid, row));
        } finally {
            buffers.unpin(block);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Rid reserveRid(String table, Row row) throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Validasi ukuran, pin, lalu reserve(size, catalog.nextSlot(table,
        // block)).
        // TODO SM1: markUnloggedDirty lalu catalog.persistAllocation sebelum return
        // RID.
        // TODO SM1: Unpin di finally, termasuk bila persistensi alokasi gagal.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.reserveRid");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void cancelReservation(String table, Rid rid)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Batalkan reservasi saja, markUnloggedDirty, lalu unpin; high-water
        // tidak turun.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.cancelReservation");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Rid insert(String table, Rid rid, Row row, long lsn)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Validasi reservasi, tulis row, lalu markDirty dan indexes.onInsert.
        // TODO SM1: Perbarui count/blok, selalu unpin, dan teruskan kegagalan parsial.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.insert");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void update(String table, Rid rid, Row row, long lsn)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(row, "row");
        TableSchema schema = catalog.getSchema(table);
        catalog.validateRow(table, row);
        String file = catalog.tableFile(table);
        if (rid.block() >= blocks.size(file)) {
            throw new StorageInternalException("no live record at " + rid);
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);
        try {
            SlottedPage page = new SlottedPage(buffer);
            Optional<byte[]> old = page.read(rid.slot());
            if (old.isEmpty()) {
                throw new StorageInternalException("no live record at " + rid);
            }
            Row before = serializer.deserialize(ByteBuffer.wrap(old.get()), schema);
            page.write(rid.slot(), serializer.serialize(row, schema));
            try {
                indexes.onUpdate(table, rid, before, row, lsn);
            } catch (StorageInternalException | RuntimeException e) {
                page.write(rid.slot(), old.get());
                throw e;
            }
            buffers.markDirty(block, lsn);
            catalog.recordChanged(table, 0, blocks.size(file));
        } finally {
            buffers.unpin(block);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean delete(String table, Rid rid, long lsn)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(rid, "rid");
        TableSchema schema = catalog.getSchema(table);
        String file = catalog.tableFile(table);
        if (rid.block() >= blocks.size(file)) {
            return false;
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);

        try {
            SlottedPage page = new SlottedPage(buffer);
            Optional<byte[]> old = page.read(rid.slot());
            if (old.isEmpty()) {
                return false;
            }

            Row before = serializer.deserialize(ByteBuffer.wrap(old.get()), schema);
            if (!page.delete(rid.slot())) {
                return false;
            }
            try {
                indexes.onDelete(table, rid, before, lsn);
            } catch (StorageInternalException | RuntimeException e) {
                page.restore(rid.slot(), old);
                throw e;
            }
            buffers.markDirty(block, lsn);
            catalog.recordChanged(table, -1, blocks.size(file));
            return true;
        } finally {
            buffers.unpin(block);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized long clearTable(String table, ToLongFunction<StoredRow> logDelete)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(logDelete, "logDelete");
        long deleted = 0;
        try (RecordCursor rows = scan(table)) {
            StoredRow row;
            while ((row = rows.next()) != null) {
                long lsn = logDelete.applyAsLong(row);
                if (delete(table, row.rid(), lsn)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /**
     * {@inheritDoc} The facade owns the build cursor; IndexManager owns final
     * registration.
     */
    @Override
    public synchronized void createIndex(IndexInfo index) throws StorageInternalException {
        ensureOpen();
        try (RecordCursor rows = scan(index.table())) {
            indexes.create(index, rows);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<Rid> lookupIndex(String index, Value key)
            throws StorageInternalException {
        ensureOpen();
        return indexes.lookup(index, key);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized List<Rid> rangeIndex(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper)
            throws StorageInternalException {
        ensureOpen();
        return indexes.range(index, lower, includeLower, upper, includeUpper);
    }

    /**
     * {@inheritDoc} Catalog save must itself force its binary file before
     * returning.
     */
    @Override
    public synchronized void flush() throws StorageInternalException {
        ensureOpen();
        buffers.flushAll();
        catalog.save();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void applyRecovery(String table, Rid rid, Optional<Row> image, long lsn)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: catalog.persistAllocation, restore RID tepat, lalu markDirty dengan
        // LSN.
        // TODO SM1: Perbarui indeks/statistik dari transisi aktual; jangan catat log
        // ulang.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.applyRecovery");
    }

    /** {@inheritDoc} Discards stale state before loading any restored data. */
    @Override
    public synchronized void reloadAfterRestore() throws StorageInternalException {
        ensureOpen();
        buffers.invalidateAll();
        blocks.closeFiles();
        indexes.invalidateAll();
        catalog.load();
        indexes.beginSession();
        for (String table : catalog.listTables()) {
            try (RecordCursor rows = scan(table)) {
                indexes.rebuild(table, rows);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void loadSampleDatabase() throws StorageInternalException {
        ensureOpen();
        samples.load(this);
    }

    /**
     * Opens the durable index-session marker and rebuilds untrusted indexes before
     * admission.
     * Called by Engine after catalog.load, before FRM recovery; applyRecovery
     * maintains the rebuilt
     * indexes while FRM replays table changes. No client transactions or cursors
     * may exist yet.
     */
    public synchronized void initializeIndexes() throws StorageInternalException {
        ensureOpen();
        if (!indexes.beginSession()) return;
        for (String table : catalog.listTables()) {
            try (RecordCursor rows = scan(table)) {
                indexes.rebuild(table, rows);
            }
        }
    }

    /**
     * {@inheritDoc} A failed initial flush leaves storage open. Once closing
     * starts, normal calls
     * are rejected and close retries only the unfinished phase; it never flushes a
     * closed buffer.
     */
    @Override
    public synchronized void close() throws StorageInternalException {
        if (closePhase == ClosePhase.OPEN) {
            flush();
            closePhase = ClosePhase.BUFFER;
        }
        if (closePhase == ClosePhase.BUFFER) {
            buffers.close();
            closePhase = ClosePhase.INDEX_MARKER;
        }
        if (closePhase == ClosePhase.INDEX_MARKER) {
            indexes.markClean();
            closePhase = ClosePhase.FILES;
        }
        if (closePhase == ClosePhase.FILES) {
            blocks.close();
            closePhase = ClosePhase.CLOSED;
        }
    }

    /**
     * Rejects use as soon as resource shutdown starts, including after a partial
     * failure.
     */
    private void ensureOpen() {
        if (closePhase != ClosePhase.OPEN) {
            throw new IllegalStateException("storage manager is closing or closed");
        }
    }

    private final class TrackedCursor implements RecordCursor {
        private final String table;
        private final RecordCursor delegate;
        private boolean closed;

        TrackedCursor(String table, RecordCursor delegate) {
            this.table = table;
            this.delegate = delegate;
        }

        @Override
        public StoredRow next() throws StorageInternalException {
            return delegate.next();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                delegate.close();
            } finally {
                release(table);
            }
        }
    }

    private synchronized void release(String table) {
        openCursors.computeIfPresent(table, (name, count) -> count > 1 ? count - 1 : null);
    }

    private void requireNoOpenCursors(String table) throws StorageInternalException {
        if (openCursors.containsKey(table)) {
            throw new StorageInternalException("table has open cursors: " + table);
        }
    }
}
