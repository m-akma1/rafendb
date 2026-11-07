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
import org.rafendb.common.error.StorageRuntimeException;
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
 * SM1 composition facade. It coordinates the catalog, serializer, block, buffer and index
 * modules. Facade calls are serialized; cursors release page latches before returning rows, and
 * DDL is refused while a cursor on the table is still open.
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
        blocks.delete(file);
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
        blocks.delete(file);
        catalog.dropTable(table, cascade);
        catalog.save();
    }

    /** {@inheritDoc} Uses the existing cursor directly, avoiding an Iterable adapter. */
    @Override
    public synchronized TableStats getStats(String table) throws StorageInternalException {
        ensureOpen();
        try (RecordCursor rows = scan(table)) {
            catalog.refreshStats(table, rows, blocks.size(catalog.tableFile(table)));
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
            Optional<byte[]> bytes = readLive(new SlottedPage(page), rid.slot());
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
        Objects.requireNonNull(row, "row");
        TableSchema schema = catalog.getSchema(table);
        catalog.validateRow(table, row);
        int size = serializer.encodedSize(row, schema);
        if (size > maxRecordSize()) {
            throw new StorageRuntimeException("row is too large for one block: " + table);
        }
        String file = catalog.tableFile(table);
        long count = blocks.size(file);
        if (count > 0) {
            Rid last = tryReserve(table, new BlockId(file, count - 1), size);
            if (last != null) {
                return last;
            }
        }
        Rid fresh = tryReserve(table, blocks.append(file), size);
        if (fresh == null) {
            throw new StorageInternalException("row does not fit in an empty block: " + table);
        }
        return fresh;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void cancelReservation(String table, Rid rid)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(rid, "rid");
        catalog.getSchema(table);
        String file = catalog.tableFile(table);
        if (rid.block() >= blocks.size(file)) {
            return;
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);
        try {
            SlottedPage page = new SlottedPage(buffer);
            if (!page.isFormatted()
                    || page.slotStatus(rid.slot()) != SlottedPage.SlotStatus.RESERVED) {
                return;
            }
            page.cancelReservation(rid.slot());
            buffers.markUnloggedDirty(block);
        } finally {
            buffers.unpin(block);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Rid insert(String table, Rid rid, Row row, long lsn)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(row, "row");
        TableSchema schema = catalog.getSchema(table);
        validateAsInternal(table, row);
        String file = catalog.tableFile(table);
        long blockCount = blocks.size(file);
        if (rid.block() >= blockCount) {
            throw new StorageInternalException("not a pending reservation: " + rid);
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);
        try {
            SlottedPage page = new SlottedPage(buffer);
            if (!page.isFormatted()
                    || page.slotStatus(rid.slot()) != SlottedPage.SlotStatus.RESERVED) {
                throw new StorageInternalException("not a pending reservation: " + rid);
            }
            pageInsert(page, rid.slot(), serializer.serialize(row, schema));
            try {
                indexes.onInsert(table, rid, row, lsn);
            } catch (StorageInternalException | RuntimeException e) {
                page.delete(rid.slot());
                throw e;
            }
            buffers.markDirty(block, lsn);
            catalog.recordChanged(table, 1, blockCount);
            return rid;
        } finally {
            buffers.unpin(block);
        }
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
        long blockCount = blocks.size(file);
        if (rid.block() >= blockCount) {
            throw new StorageInternalException("no live record at " + rid);
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);
        try {
            SlottedPage page = new SlottedPage(buffer);
            Optional<byte[]> old = readLive(page, rid.slot());
            if (old.isEmpty()) {
                throw new StorageInternalException("no live record at " + rid);
            }
            Row before = serializer.deserialize(ByteBuffer.wrap(old.get()), schema);
            pageUpdate(page, rid.slot(), serializer.serialize(row, schema));
            try {
                indexes.onUpdate(table, rid, before, row, lsn);
            } catch (StorageInternalException | RuntimeException e) {
                pageUpdate(page, rid.slot(), old.get());
                throw e;
            }
            buffers.markDirty(block, lsn);
            catalog.recordChanged(table, 0, blockCount);
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
        long blockCount = blocks.size(file);
        if (rid.block() >= blockCount) {
            return false;
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);
        try {
            SlottedPage page = new SlottedPage(buffer);
            Optional<byte[]> old = readLive(page, rid.slot());
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
                pageRestore(page, rid.slot(), old.get());
                throw e;
            }
            buffers.markDirty(block, lsn);
            catalog.recordChanged(table, -1, blockCount);
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

    /** {@inheritDoc} The facade owns the build cursor; IndexManager owns final registration. */
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

    /** {@inheritDoc} Catalog save must itself force its binary file before returning. */
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
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(image, "image");
        TableSchema schema;
        try {
            schema = catalog.getSchema(table);
        } catch (StorageRuntimeException e) {
            throw new StorageInternalException(e.getMessage(), e);
        }
        String file = catalog.tableFile(table);
        byte[] bytes = null;
        if (image.isPresent()) {
            validateAsInternal(table, image.get());
            bytes = serializer.serialize(image.get(), schema);
        }
        catalog.persistAllocation(table, rid);
        if (image.isPresent()) ensureBlocks(file, rid.block());
        long blockCount = blocks.size(file);
        if (image.isEmpty() && rid.block() >= blockCount) {
            return;
        }
        BlockId block = new BlockId(file, rid.block());
        ByteBuffer buffer = buffers.pin(block);
        try {
            byte[] originalPage = new byte[buffer.capacity()];
            buffer.duplicate().clear().get(originalPage);
            SlottedPage page = new SlottedPage(buffer);
            if (!page.isFormatted()) {
                if (image.isEmpty()) {
                    return;
                }
                page.initialize();
            }
            Optional<byte[]> old = readLive(page, rid.slot());
            Row before =
                    old.isPresent()
                            ? serializer.deserialize(ByteBuffer.wrap(old.get()), schema)
                            : null;
            long delta;
            try {
                if (image.isPresent()) {
                    if (before == null) {
                        if (page.slotStatus(rid.slot()) == SlottedPage.SlotStatus.RESERVED) {
                            pageInsert(page, rid.slot(), bytes);
                        } else {
                            pageRestore(page, rid.slot(), bytes);
                        }
                        buffers.markDirty(block, lsn);
                        indexes.onInsert(table, rid, image.get(), lsn);
                        delta = 1;
                    } else {
                        pageUpdate(page, rid.slot(), bytes);
                        buffers.markDirty(block, lsn);
                        indexes.onUpdate(table, rid, before, image.get(), lsn);
                        delta = 0;
                    }
                } else if (before != null) {
                    page.delete(rid.slot());
                    buffers.markDirty(block, lsn);
                    indexes.onDelete(table, rid, before, lsn);
                    delta = -1;
                } else {
                    if (page.slotStatus(rid.slot()) == SlottedPage.SlotStatus.RESERVED) {
                        page.cancelReservation(rid.slot());
                        buffers.markDirty(block, lsn);
                    }
                    return;
                }
            } catch (StorageInternalException | RuntimeException e) {
                buffer.duplicate().clear().put(originalPage);
                buffers.markDirty(block, lsn);
                throw e;
            }
            catalog.recordChanged(table, delta, blockCount);
        } finally {
            buffers.unpin(block);
        }
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
     * Opens the durable index-session marker and rebuilds untrusted indexes before admission.
     * Called by Engine after catalog.load, before FRM recovery; applyRecovery maintains the
     * rebuilt indexes while FRM replays table changes. No client transactions or cursors may
     * exist yet.
     */
    public synchronized void initializeIndexes() throws StorageInternalException {
        ensureOpen();
        if (!indexes.beginSession()) {
            return;
        }
        for (String table : catalog.listTables()) {
            try (RecordCursor rows = scan(table)) {
                indexes.rebuild(table, rows);
            }
        }
    }

    /**
     * {@inheritDoc} A failed initial flush leaves storage open. Once closing starts, normal
     * calls are rejected and close retries only the unfinished phase; it never flushes a closed
     * buffer.
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

    /** Rejects use as soon as shutdown starts, including after a partial failure. */
    private void ensureOpen() {
        if (closePhase != ClosePhase.OPEN) {
            throw new IllegalStateException("storage manager is closing or closed");
        }
    }

    /** Reads the live record in a slot; a page that was never formatted holds none. */
    private Optional<byte[]> readLive(SlottedPage page, int slot) {
        if (!page.isFormatted()) {
            return Optional.empty();
        }
        return page.read(slot);
    }

    /** Stores a record in a RESERVED slot; a refused write becomes the contract exception. */
    private void pageInsert(SlottedPage page, int slot, byte[] bytes)
            throws StorageInternalException {
        try {
            page.insert(slot, bytes);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new StorageInternalException("cannot write slot " + slot, e);
        }
    }

    /** Replaces the record of a LIVE slot; a refused write becomes the contract exception. */
    private void pageUpdate(SlottedPage page, int slot, byte[] bytes)
            throws StorageInternalException {
        try {
            page.update(slot, bytes);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new StorageInternalException("cannot write slot " + slot, e);
        }
    }

    /** Puts a record into a FREE slot, for recovery and for undoing a delete. */
    private void pageRestore(SlottedPage page, int slot, byte[] bytes)
            throws StorageInternalException {
        try {
            page.restore(slot, bytes);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new StorageInternalException("cannot write slot " + slot, e);
        }
    }

    /** Re-reports a client-side validation failure as the internal type some methods need. */
    private void validateAsInternal(String table, Row row) throws StorageInternalException {
        try {
            catalog.validateRow(table, row);
        } catch (StorageRuntimeException e) {
            throw new StorageInternalException(e.getMessage(), e);
        }
    }

    /** Counts one open cursor per table until it is closed, so DDL can refuse to run. */
    private final class TrackedCursor implements RecordCursor {
        private final String table;
        private final RecordCursor delegate;
        private boolean closed;

        /** Wraps a cursor and remembers which table it reads. */
        TrackedCursor(String table, RecordCursor delegate) {
            this.table = table;
            this.delegate = delegate;
        }

        /** {@inheritDoc} */
        @Override
        public StoredRow next() throws StorageInternalException {
            return delegate.next();
        }

        /** {@inheritDoc} Idempotent; the open-cursor count drops exactly once. */
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

    /** Lowers the open-cursor count of a table and forgets the table when it reaches zero. */
    private synchronized void release(String table) {
        openCursors.computeIfPresent(table, (name, count) -> count > 1 ? count - 1 : null);
    }

    /** Rejects DDL while any cursor on the table is still open. */
    private void requireNoOpenCursors(String table) throws StorageInternalException {
        if (openCursors.containsKey(table)) {
            throw new StorageInternalException("table has open cursors: " + table);
        }
    }

    /** Largest record a completely empty block can hold. */
    private int maxRecordSize() {
        return new SlottedPage(ByteBuffer.allocate(blocks.blockSize())).maxRecordSize();
    }

    /** Appends and formats blocks until the file contains the given block number. */
    private void ensureBlocks(String file, long lastBlock) throws StorageInternalException {
        while (blocks.size(file) <= lastBlock) {
            BlockId fresh = blocks.append(file);
            ByteBuffer buffer = buffers.pin(fresh);
            try {
                SlottedPage page = new SlottedPage(buffer);
                if (!page.isFormatted()) {
                    page.initialize();
                }
                buffers.markUnloggedDirty(fresh);
            } finally {
                buffers.unpin(fresh);
            }
        }
    }

    /**
     * Reserves a slot in one block, or returns null when the block has no room. Slots at or below
     * the allocation high-water were handed out before a crash, so they are skipped rather than
     * reused.
     */
    private Rid tryReserve(String table, BlockId block, int size) throws StorageInternalException {
        ByteBuffer buffer = buffers.pin(block);
        try {
            SlottedPage page = new SlottedPage(buffer);
            if (!page.isFormatted()) {
                page.initialize();
            }
            int slot = page.reserve(size);
            while (slot >= 0 && slot < catalog.nextSlot(table, block.number())) {
                page.cancelReservation(slot);
                slot = page.reserve(size);
            }
            if (slot < 0) {
                return null;
            }
            Rid rid = new Rid(block.number(), slot);
            buffers.markUnloggedDirty(block);
            try {
                catalog.persistAllocation(table, rid);
            } catch (StorageInternalException | RuntimeException e) {
                page.cancelReservation(slot);
                throw e;
            }
            return rid;
        } finally {
            buffers.unpin(block);
        }
    }
}
