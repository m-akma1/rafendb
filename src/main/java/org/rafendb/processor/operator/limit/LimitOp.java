package org.rafendb.processor.operator.limit;

import java.util.List;
import java.util.Objects;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Operator LIMIT (QP-16 bonus). Cuma nerusin N baris pertama dari child, sisanya ga dibaca. RID
 * ikut diterusin.
 *
 * <p>Ga thread-safe, satu instance buat satu eksekusi.
 */
public final class LimitOp implements Operator {
    private final Operator child;
    private final int limit;
    private int count;
    private boolean open;

    /**
     * Bikin LimitOp, child belum dibuka.
     *
     * @param child sumber baris, lifecycle-nya diurus LimitOp
     * @param limit jumlah baris maksimal, 0 artinya hasil kosong
     * @throws NullPointerException kalau child null
     * @throws IllegalArgumentException kalau limit negatif
     */
    public LimitOp(Operator child, int limit) {
        this.child = Objects.requireNonNull(child, "child");
        if (limit < 0) {
            throw new IllegalArgumentException("Negative limit");
        }
        this.limit = limit;
    }

    /**
     * Buka child dari awal terus reset hitungan. Kalau child gagal dibuka, operator ini tetap
     * ketutup, jadi next() bakal error.
     */
    @Override
    public void open() {
        open = false;
        child.open();
        count = 0;
        open = true;
    }

    /** Sama kayak schema child. */
    @Override
    public List<ColumnSchema> schema() {
        return child.schema();
    }

    /**
     * Balikin baris berikutnya dari child, atau null kalau child habis. Kalau udah dapet limit
     * baris, langsung null tanpa baca child lagi.
     *
     * @throws IllegalStateException kalau belum dibuka atau udah ditutup
     */
    @Override
    public ResultRow next() {
        if (!open) {
            throw new IllegalStateException("LimitOp is not open");
        }
        if (count >= limit) {
            return null;
        }
        ResultRow row = child.next();
        if (row == null) {
            return null;
        }
        count++;
        return row;
    }

    /** Tutup child. Aman dipanggil sebelum open atau berkali-kali. */
    @Override
    public void close() {
        open = false;
        child.close();
    }

    /**
     * Bungkus hasil translate SELECT pakai LimitOp, dipanggil orchestrator. LIMIT-nya diambil dari
     * {@link org.rafendb.common.query.SelectQuery#limit()} karena QO ga naruh LIMIT di plan.
     *
     * @param rows hasil translate SELECT
     * @param limit jumlah baris maksimal, null kalau query ga pakai LIMIT
     * @return rows yang sama kalau limit null, selain itu rows baru yang dibungkus LimitOp
     * @throws NullPointerException kalau rows null
     * @throws IllegalArgumentException kalau limit negatif
     */
    public static ExecutionPlan.Rows wrap(ExecutionPlan.Rows rows, Integer limit) {
        Objects.requireNonNull(rows, "rows");
        if (limit == null) {
            return rows;
        }
        return new ExecutionPlan.Rows(rows.columns(), new LimitOp(rows.operator(), limit));
    }
}
