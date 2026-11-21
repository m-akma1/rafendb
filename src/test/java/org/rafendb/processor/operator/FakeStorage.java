package org.rafendb.processor.operator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageDefinition;
import org.rafendb.api.storage.StorageQuery;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;

/**
 * StorageQuery dan StorageDefinition palsu buat test QP: satu tabel di memori. Method mana pun bisa disuruh gagal di
 * panggilan ke-n ({@link #failOn}). Tiap panggilan yang ngubah sesuatu dicatat ke {@link EventLog}:
 *
 * <pre>
 * sm.scan | sm.reserveRid | sm.cancelReservation | sm.createIndex | sm.createTable | sm.dropTable
 * sm.insert lsn=N | sm.update lsn=N | sm.delete lsn=N | sm.&lt;method&gt; (fails)
 * </pre>
 *
 * <p>encodeRow sukses ga dicatat (dipanggil tiap baris). Image-nya opaque; {@link #decodeRow}
 * balikin baris aslinya, jadi test bisa ngecek isi log.
 */
public final class FakeStorage implements StorageQuery, StorageDefinition {
    private record Failure(int nth, Exception error) {}

    private final TableSchema schema;
    private final Map<Rid, Row> rows = new LinkedHashMap<>();
    private final EventLog events;
    private final List<Rid> reserved = new ArrayList<>();
    private final List<Rid> cancelled = new ArrayList<>();
    private final List<IndexInfo> indexes = new ArrayList<>();
    private final List<FakeCursor> cursors = new ArrayList<>();
    private final List<Row> images = new ArrayList<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final Map<String, Failure> failures = new HashMap<>();
    private boolean failNextRead;
    private int nextSlot = 50;

    /** Storage palsu dengan log kejadiannya sendiri. */
    public FakeStorage(TableSchema schema, List<StoredRow> rows) {
        this(schema, rows, new EventLog());
    }

    /** Storage palsu yang nyatet ke log bersama (misalnya punya FakeCcm). */
    public FakeStorage(TableSchema schema, List<StoredRow> rows, EventLog events) {
        this.schema = schema;
        this.events = events;
        for (StoredRow row : rows) {
            this.rows.put(row.rid(), row.row());
        }
    }

    /** Bikin panggilan ke-{@code nth} (mulai dari 1) ke {@code method} gagal pakai {@code error}. */
    public void failOn(String method, int nth, Exception error) {
        failures.put(method, new Failure(nth, error));
    }

    /** Bikin next() pertama di cursor berikutnya gagal pakai StorageInternalException. */
    public void failNextRead() {
        failNextRead = true;
    }

    /** Isi tabel sekarang, urut sesuai urutan masuk. Bisa diubah langsung sama test. */
    public Map<Rid, Row> rows() {
        return rows;
    }

    public List<Rid> reserved() {
        return reserved;
    }

    public List<Rid> cancelled() {
        return cancelled;
    }

    public List<IndexInfo> indexes() {
        return indexes;
    }

    public int openedCursors() {
        return cursors.size();
    }

    public int openCursors() {
        return (int) cursors.stream().filter(cursor -> !cursor.closed).count();
    }

    /** Balikin baris dari image buatan {@link #encodeRow}. */
    public Row decodeRow(byte[] image) {
        return images.get(Integer.parseInt(new String(image, StandardCharsets.UTF_8)));
    }

    @Override
    public TableSchema getSchema(String table) {
        if (!schema.table().equals(table)) {
            throw new StorageRuntimeException("unknown table: " + table);
        }
        return schema;
    }

    @Override
    public byte[] encodeRow(String table, Row row) {
        getSchema(table);
        uncheckedCall("encodeRow");
        images.add(row);
        return String.valueOf(images.size() - 1).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public RecordCursor scan(String table) throws StorageInternalException {
        getSchema(table);
        call("scan");
        events.add("sm.scan");
        List<StoredRow> snapshot = new ArrayList<>();
        rows.forEach((rid, row) -> snapshot.add(new StoredRow(rid, row)));
        FakeCursor cursor = new FakeCursor(snapshot, failNextRead);
        failNextRead = false;
        cursors.add(cursor);
        return cursor;
    }

    @Override
    public Rid reserveRid(String table, Row row) throws StorageInternalException {
        getSchema(table);
        call("reserveRid");
        Rid rid = new Rid(7, nextSlot++);
        reserved.add(rid);
        events.add("sm.reserveRid");
        return rid;
    }

    @Override
    public void cancelReservation(String table, Rid rid) throws StorageInternalException {
        call("cancelReservation");
        cancelled.add(rid);
        events.add("sm.cancelReservation");
    }

    @Override
    public Rid insert(String table, Rid rid, Row row, long lsn) throws StorageInternalException {
        call("insert");
        rows.put(rid, row);
        events.add("sm.insert lsn=" + lsn);
        return rid;
    }

    @Override
    public void update(String table, Rid rid, Row row, long lsn) throws StorageInternalException {
        call("update");
        rows.put(rid, row);
        events.add("sm.update lsn=" + lsn);
    }

    @Override
    public boolean delete(String table, Rid rid, long lsn) throws StorageInternalException {
        call("delete");
        events.add("sm.delete lsn=" + lsn);
        return rows.remove(rid) != null;
    }

    @Override
    public void createIndex(IndexInfo index) throws StorageInternalException {
        call("createIndex");
        indexes.add(index);
        events.add("sm.createIndex");
    }

    /** Cuma dicatat; tabel palsunya tetap satu. */
    @Override
    public void createTable(TableSchema schema) throws StorageInternalException {
        call("createTable");
        events.add("sm.createTable");
    }

    /** Cuma dicatat; tabel palsunya tetap satu. */
    @Override
    public void dropTable(String table, boolean cascade) throws StorageInternalException {
        call("dropTable");
        events.add("sm.dropTable");
    }

    /** Hitung panggilan; lempar error yang diatur kalau ini panggilan ke-n-nya. */
    private void call(String method) throws StorageInternalException {
        int n = calls.merge(method, 1, Integer::sum);
        Failure failure = failures.get(method);
        if (failure == null || failure.nth() != n) {
            return;
        }
        events.add("sm." + method + " (fails)");
        if (failure.error() instanceof StorageInternalException e) {
            throw e;
        }
        throw (RuntimeException) failure.error();
    }

    private void uncheckedCall(String method) {
        try {
            call(method);
        } catch (StorageInternalException e) {
            throw new AssertionError(method + " cannot throw a checked exception", e);
        }
    }

    @Override
    public List<String> listTables() {
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
    public List<Rid> lookupIndex(String index, Value key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Rid> rangeIndex(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper) {
        throw new UnsupportedOperationException();
    }

    /** Cursor palsu atas snapshot tabel; bisa gagal pas dibaca. */
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
                throw new StorageInternalException("page read failed");
            }
            return position < rows.size() ? rows.get(position++) : null;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
