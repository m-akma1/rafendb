package org.rafendb.api.processor;

import org.rafendb.common.expr.Expression;
import org.rafendb.common.expr.Predicate;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.TruthValue;
import org.rafendb.common.type.Value;

/** Proposed thread-safe evaluator; separate expression API and TruthValue await QO2 agreement. */
public interface ExpressionEvaluator {
    /**
     * Evaluates a non-null expression against a non-null row (empty is valid for literals).
     * @return non-null scalar value
     * @throws NullPointerException for null input
     * @throws IllegalArgumentException for invalid expressions, columns, or operand types
     * @throws ArithmeticException for division by zero
     */
    Value evaluate(Expression expression, Row row);

    /**
     * Evaluates a non-null predicate against a non-null row.
     * @return whether the row satisfies the predicate
     * @throws NullPointerException for null input
     * @throws IllegalArgumentException for invalid conditions, columns, or operand types
     * @throws ArithmeticException for division by zero
     */
    default boolean test(Predicate predicate, Row row) {
        return evaluateCondition(predicate, row).isTrue();
    }

    /**
     * Evaluates a non-null predicate and row to non-null TRUE, FALSE, or UNKNOWN.
     * SQL NULL in comparisons yields UNKNOWN in the real evaluator; mocks return configured truth.
     * @throws NullPointerException for Java null input
     * @throws IllegalArgumentException for invalid conditions, columns, or operand types
     * @throws ArithmeticException for division by zero
     */
    TruthValue evaluateCondition(Predicate predicate, Row row);
}
