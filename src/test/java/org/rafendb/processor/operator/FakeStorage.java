package org.rafendb.processor.operator;

import java.util.ArrayList;
import java.util.List;
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

/**
 * StorageManager palsu buat test: isinya satu tabel di memori, nyatet cursor yang dibuka sama
 * ditutup, dan bisa disuruh lempar StorageInternalException. Method lain ga dipakai.
 */
public final class FakeStorage implements StorageManager {
    private final TableSchema schema;
    private final List<StoredRow> rows;
    private final List<FakeCursor> cursors = new ArrayList<>();
    private boolean failNextScan;
    private boolean failNextRead;

    /** Bikin storage palsu dengan satu tabel. */
    public FakeStorage(TableSchema schema, List<StoredRow> rows) {
        this.schema = schema;
        this.rows = rows;
    }

    /** Bikin scan() berikutnya gagal pakai StorageInternalException. */
    public void failNextScan() {
        failNextScan = true;
    }

    /** Bikin next() di cursor berikutnya gagal pakai StorageInternalException. */
    public void failNextRead() {
        failNextRead = true;
    }

    /** Berapa cursor yang pernah dibuka. */
    public int getOpenedCursorCount() {
        return cursors.size();
    }

    /** Berapa cursor yang masih kebuka. */
    public int getOpenCursorCount() {
        int count = 0;
        for (FakeCursor cursor : cursors) {
            if (!cursor.closed) {
                count++;
            }
        }
        return count;
    }

    @Override
    public TableSchema getSchema(String table) {
        if (!schema.table().equals(table)) {
            throw new StorageRuntimeException("unknown table: " + table);
        }
        return schema;
    }

    @Override
    public RecordCursor scan(String table) throws StorageInternalException {
        getSchema(table);
        if (failNextScan) {
            failNextScan = false;
            throw new StorageInternalException("Disk read failed");
        }
        FakeCursor cursor = new FakeCursor(rows, failNextRead);
        failNextRead = false;
        cursors.add(cursor);
        return cursor;
    }

    @Override
    public List<String> listTables() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<ReferencingForeignKey> listReferencingForeignKeys(String table) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createTable(TableSchema schema) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void dropTable(String table, boolean cascade) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TableStats getStats(String table) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<IndexInfo> listIndexes(String table) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StoredRow> fetch(String table, Rid rid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rid reserveRid(String table, Row row) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void cancelReservation(String table, Rid rid) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Rid insert(String table, Rid rid, Row row, long lsn) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void update(String table, Rid rid, Row row, long lsn) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean delete(String table, Rid rid, long lsn) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long clearTable(String table, ToLongFunction<StoredRow> logDelete) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void createIndex(IndexInfo index) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Rid> lookupIndex(String index, Value key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Rid> rangeIndex(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void flush() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void applyRecovery(String table, Rid rid, Optional<Row> image, long lsn) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reloadAfterRestore() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void loadSampleDatabase() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }

    /** Cursor palsu: ngeluarin baris dari list, bisa gagal pas dibaca. */
    private static final class FakeCursor implements RecordCursor {
        private final List<StoredRow> rows;
        private final boolean failRead;
        private int position;
        private boolean closed;

        FakeCursor(List<StoredRow> rows, boolean failRead) {
            this.rows = rows;
            this.failRead = failRead;
        }

        @Override
        public StoredRow next() throws StorageInternalException {
            if (closed) {
                throw new IllegalStateException("FakeCursor is closed");
            }
            if (failRead) {
                throw new StorageInternalException("Page read failed");
            }
            if (position >= rows.size()) {
                return null;
            }
            StoredRow row = rows.get(position);
            position++;
            return row;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
