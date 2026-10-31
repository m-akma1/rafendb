package org.rafendb.mock.processor;

import java.util.List;
import java.util.Objects;
import org.rafendb.api.processor.ExpressionEvaluator;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.ResultRow;
import org.rafendb.common.type.TruthValue;
import org.rafendb.common.type.Value;

public final class MockExpressionEvaluator implements ExpressionEvaluator {
    private final Value value;
    private final TruthValue truth;

    /** Mengatur nilai hasil mock dan mengubah matches menjadi TRUE atau FALSE. */
    public MockExpressionEvaluator(Value value, boolean matches) {
        this(value, matches ? TruthValue.TRUE : TruthValue.FALSE);
    }

    /** Mengatur nilai hasil mock serta TRUE, FALSE, atau UNKNOWN, keduanya tidak boleh Java null. */
    public MockExpressionEvaluator(Value value, TruthValue truth) {
        this.value = Objects.requireNonNull(value, "value");
        this.truth = Objects.requireNonNull(truth, "truth");
    }

    /** {@inheritDoc} Mock ini hanya menolak input Java null dan mengembalikan hasil konfigurasi. */
    @Override
    public Value evaluate(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(schema, "schema");
        return value;
    }

    /** {@inheritDoc} Mock ini hanya menolak input Java null dan mengembalikan hasil konfigurasi. */
    @Override
    public TruthValue evaluateCondition(Expr condition, ResultRow row, List<ColumnSchema> schema) {
        Objects.requireNonNull(condition);
        Objects.requireNonNull(row);
        Objects.requireNonNull(schema);
        return truth;
    }
}
