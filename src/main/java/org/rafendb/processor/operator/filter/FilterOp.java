package org.rafendb.processor.operator.filter;

import java.util.List;
import java.util.Objects;
import org.rafendb.api.processor.ExpressionEvaluator;
import org.rafendb.api.processor.Operator;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.ResultRow;

/**
 * Operator WHERE (QP-06). Cuma baris yang kondisinya TRUE yang diterusin, FALSE sama UNKNOWN
 * dibuang. RID ikut diterusin biar bisa dipakai DELETE/UPDATE.
 *
 * <p>Ga thread-safe, satu instance buat satu eksekusi.
 */
public final class FilterOp implements Operator {
    private final Operator child;
    private final Expr condition;
    private final ExpressionEvaluator evaluator;
    private List<ColumnSchema> childSchema;
    private boolean open;

    /**
     * Bikin FilterOp, child belum dibuka.
     *
     * @param child sumber baris, lifecycle-nya diurus FilterOp
     * @param condition kondisi WHERE
     * @param evaluator evaluator buat nilai kondisi
     * @throws NullPointerException kalau ada argumen yang null
     */
    public FilterOp(Operator child, Expr condition, ExpressionEvaluator evaluator) {
        this.child = Objects.requireNonNull(child, "child");
        this.condition = Objects.requireNonNull(condition, "condition");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /**
     * Buka child dari awal terus simpen schema-nya. Kalau child gagal dibuka, operator ini tetap
     * ketutup, jadi next() bakal error.
     */
    @Override
    public void open() {
        open = false;
        child.open();
        childSchema = child.schema();
        open = true;
    }

    /** Sama kayak schema child. */
    @Override
    public List<ColumnSchema> schema() {
        return child.schema();
    }

    /**
     * Balikin baris berikutnya yang lolos, atau null kalau child udah habis. Exception dari
     * evaluator dilempar terus.
     *
     * @throws IllegalStateException kalau belum dibuka atau udah ditutup
     */
    @Override
    public ResultRow next() {
        if (!open) {
            throw new IllegalStateException("FilterOp is not open");
        }
        ResultRow row = child.next();
        while (row != null) {
            if (evaluator.test(condition, row, childSchema)) {
                return row;
            }
            row = child.next();
        }
        return null;
    }

    /** Tutup child. Aman dipanggil sebelum open atau berkali-kali. */
    @Override
    public void close() {
        open = false;
        child.close();
    }
}
