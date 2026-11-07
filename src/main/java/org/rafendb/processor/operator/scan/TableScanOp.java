package org.rafendb.processor.operator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.processor.Operator;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.ResultRow;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.processor.error.QueryExecutionException;

/**
 * Operator FROM tabel (QP-05). Baca semua baris tabel lewat SM, kolomnya urut katalog dan RID-nya
 * ikut. Qualifier di schema = alias, atau nama tabel kalau alias-nya null.
 *
 * <p>Lock baca belum dipanggil, ditambahin setelah helper QP5 ada. Ga thread-safe, satu instance
 * buat satu eksekusi.
 */
public final class TableScanOp implements Operator {
    private final StorageManager storage;
    private final String table;
    private final List<ColumnSchema> schema;
    private RecordCursor cursor;

    /**
     * Bikin TableScanOp. Schema langsung disiapin dari katalog, cursor belum dibuka.
     *
     * @param storage storage manager buat baca tabel
     * @param table nama tabel
     * @param alias alias tabel, boleh null
     * @throws NullPointerException kalau storage atau table null
     * @throws org.rafendb.common.error.StorageRuntimeException kalau tabelnya ga ada
     */
    public TableScanOp(StorageManager storage, String table, String alias) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.table = Objects.requireNonNull(table, "table");
        String qualifier = table;
        if (alias != null) {
            qualifier = alias;
        }
        TableSchema tableSchema = storage.getSchema(table);
        List<ColumnSchema> columns = new ArrayList<>();
        for (Column column : tableSchema.columns()) {
            Expr.ColumnRef header = new Expr.ColumnRef(qualifier, column.name());
            columns.add(new ColumnSchema(header, column.type()));
        }
        schema = List.copyOf(columns);
    }

    /**
     * Buka scan baru dari awal, cursor lama ditutup dulu. Kalau scan gagal, operator ini tetap
     * ketutup, jadi next() bakal error.
     *
     * @throws QueryExecutionException kalau SM gagal buka scan
     */
    @Override
    public void open() {
        closeCursor();
        try {
            cursor = storage.scan(table);
        } catch (StorageInternalException e) {
            throw new QueryExecutionException("Failed to scan table " + table, e);
        }
    }

    /** Schema tabel yang udah disiapin di constructor. */
    @Override
    public List<ColumnSchema> schema() {
        return schema;
    }

    /**
     * Balikin baris berikutnya lengkap sama RID-nya, atau null kalau tabel udah habis.
     *
     * @throws IllegalStateException kalau belum dibuka atau udah ditutup
     * @throws QueryExecutionException kalau SM gagal baca halaman berikutnya
     */
    @Override
    public ResultRow next() {
        if (cursor == null) {
            throw new IllegalStateException("TableScanOp is not open");
        }
        StoredRow stored;
        try {
            stored = cursor.next();
        } catch (StorageInternalException e) {
            throw new QueryExecutionException("Failed to read table " + table, e);
        }
        if (stored == null) {
            return null;
        }
        return new ResultRow(stored.row().values(), stored.rid());
    }

    /** Tutup cursor. Aman dipanggil sebelum open atau berkali-kali. */
    @Override
    public void close() {
        closeCursor();
    }

    /**
     * Kosongin cursor dulu baru ditutup, biar kalau close() gagal operatornya tetap dianggap
     * ketutup.
     */
    private void closeCursor() {
        if (cursor != null) {
            RecordCursor old = cursor;
            cursor = null;
            old.close();
        }
    }
}
