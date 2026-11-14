package org.rafendb.processor.operator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;
import org.rafendb.processor.txn.TableLocks;

/**
 * Operator FROM tabel (QP-05). Baca semua baris tabel lewat SM, kolomnya urut katalog dan RID-nya
 * ikut. Qualifier di schema = alias, atau nama tabel kalau alias-nya null.
 *
 * <p>Tiap open() minta lock baca tabel dulu lewat TableLocks, baru buka scan. Ga thread-safe, satu
 * instance buat satu eksekusi.
 */
public final class TableScanOp implements Operator {
    private final ExecutionContext context;
    private final StorageManager storage;
    private final String table;
    private final List<ColumnSchema> schema;
    private RecordCursor cursor;

    /**
     * Bikin TableScanOp. Schema langsung disiapin dari katalog, cursor belum dibuka.
     *
     * @param context konteks eksekusi session, sumber storage manager sama transaksinya
     * @param table nama tabel
     * @param alias alias tabel, boleh null
     * @throws NullPointerException kalau context, storage manager-nya, atau table null
     * @throws org.rafendb.common.error.StorageRuntimeException kalau tabelnya ga ada
     */
    public TableScanOp(ExecutionContext context, String table, String alias) {
        this.context = Objects.requireNonNull(context, "context");
        this.storage = Objects.requireNonNull(context.getStorageManager(), "storage");
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
     * Buka scan baru dari awal: cursor lama ditutup, minta lock baca, baru scan. Kalau lock atau
     * scan gagal, operator ini tetap ketutup, jadi next() bakal error.
     *
     * @throws TransactionAbortedException kalau CCM bilang transaksinya batal
     * @throws IllegalStateException kalau session ga valid atau ga ada transaksi aktif
     * @throws QueryExecutionException kalau SM gagal buka scan
     */
    @Override
    public void open() {
        closeCursor();
        TableLocks.acquireRead(context, table);
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
