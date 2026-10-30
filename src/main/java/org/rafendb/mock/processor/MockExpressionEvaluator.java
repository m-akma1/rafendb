package org.rafendb.mock.processor;

import java.util.Objects;
import org.rafendb.api.processor.ExpressionEvaluator;
import org.rafendb.common.expr.Expression;
import org.rafendb.common.expr.Predicate;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.TruthValue;
import org.rafendb.common.type.Value;

/** Thread-safe dummy: returns configured constants without evaluating the expression. */
public final class MockExpressionEvaluator implements ExpressionEvaluator {
    private final Value value;
    private final TruthValue truth;

    /** Configures non-null dummy value and dummy condition result. */
    public MockExpressionEvaluator(Value value, boolean matches) {
        this(value, matches ? TruthValue.TRUE : TruthValue.FALSE);
    }

    /** Configures a non-null scalar and three-valued result; no expression is evaluated. */
    public MockExpressionEvaluator(Value value, TruthValue truth) {
        this.value = Objects.requireNonNull(value, "value");
        this.truth = Objects.requireNonNull(truth, "truth");
    }

    /** {@inheritDoc} Only input null checks are performed by this mock. */
    @Override
    public Value evaluate(Expression expression, Row row) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(row, "row");
        return value;
    }

    /** {@inheritDoc} Only input null checks are performed by this mock. */
    @Override
    public TruthValue evaluateCondition(Predicate predicate, Row row) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(row, "row");
        return truth;
    }
}
