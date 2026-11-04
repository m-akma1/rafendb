package org.rafendb.storage;

import java.util.List;
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
import org.rafendb.storage.file.BlockManager;
import org.rafendb.storage.index.IndexManager;
import org.rafendb.storage.record.RecordSerializer;
import org.rafendb.storage.record.TableScan;
import org.rafendb.storage.seed.SampleDatabaseLoader;

/**
 * SM1 composition facade. Module dependencies and delegations are wired; physical operations
 * marked TODO intentionally fail until their owners implement them. Facade calls are serialized;
 * cursors release page latches before returning rows. DDL must still exclude active cursors.
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
        // TODO SM1: Validasi skema, buat berkas, lalu registerTable/save.
        // TODO SM1: Bersihkan DDL parsial; PK tidak otomatis membuat indeks.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.createTable");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void dropTable(String table, boolean cascade)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Hentikan akses tabel, validasi DROP, lalu hapus indeks dan cache.
        // TODO SM1: Hapus berkas/metadata; CASCADE menghapus FK tanpa menghapus baris anak.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.dropTable");
    }

    /** {@inheritDoc} Uses the existing cursor directly, avoiding an Iterable<Row> adapter. */
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
        return new TableScan(catalog.tableFile(table), schema, blocks, buffers, serializer);
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<StoredRow> fetch(String table, Rid rid)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Ambil skema/berkas, pin, baca/dekode slot, lalu unpin di finally.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.fetch");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Rid reserveRid(String table, Row row) throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Validasi ukuran, pin, lalu reserve(size, catalog.nextSlot(table, block)).
        // TODO SM1: markUnloggedDirty lalu catalog.persistAllocation sebelum return RID.
        // TODO SM1: Unpin di finally, termasuk bila persistensi alokasi gagal.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.reserveRid");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void cancelReservation(String table, Rid rid)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Batalkan reservasi saja, markUnloggedDirty, lalu unpin; high-water tidak turun.
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
        // TODO SM1: Tulis serializer.serialize sebagai byte[] di slot sama; markDirty dan onUpdate.
        // TODO SM1: Perbarui statistik; jangan relokasi atau samakan error I/O dengan ruang penuh.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.update");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean delete(String table, Rid rid, long lsn)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Baca before-image, hapus slot aktif, lalu perbarui dirty, indeks, dan count.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.delete");
    }

    /** {@inheritDoc} */
    @Override
    public synchronized long clearTable(String table, ToLongFunction<StoredRow> logDelete)
            throws StorageInternalException {
        ensureOpen();
        // TODO SM1: Scan bertahap, log sebelum delete; pertahankan hasil parsial jika gagal.
        throw new UnsupportedOperationException("SM1: StorageManagerImpl.clearTable");
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
        // TODO SM1: catalog.persistAllocation, restore RID tepat, lalu markDirty dengan LSN.
        // TODO SM1: Perbarui indeks/statistik dari transisi aktual; jangan catat log ulang.
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
     * Opens the durable index-session marker and rebuilds untrusted indexes before admission.
     * Called by Engine after catalog.load, before FRM recovery; applyRecovery maintains the rebuilt
     * indexes while FRM replays table changes. No client transactions or cursors may exist yet.
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
     * {@inheritDoc} A failed initial flush leaves storage open. Once closing starts, normal calls
     * are rejected and close retries only the unfinished phase; it never flushes a closed buffer.
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

    /** Rejects use as soon as resource shutdown starts, including after a partial failure. */
    private void ensureOpen() {
        if (closePhase != ClosePhase.OPEN) {
            throw new IllegalStateException("storage manager is closing or closed");
        }
    }
}
