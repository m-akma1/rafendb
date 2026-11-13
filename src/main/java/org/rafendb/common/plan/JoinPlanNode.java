package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * Binary node that joins the rows of two children with a given algorithm.
 *
 * <p>Immutable and thread-safe.
 *
 * @param algorithm the join algorithm, not null
 * @param condition the JOIN ... ON condition, or {@code null} for NATURAL JOIN
 * @param left the left (outer) input, not null
 * @param right the right (inner) input, not null
 */
public record JoinPlanNode(JoinAlgorithm algorithm, Expr condition, PlanNode left, PlanNode right)
        implements PlanNode {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if {@code algorithm}, {@code left} or {@code right} is null
     */
    public JoinPlanNode {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
    }

    /** {@inheritDoc} Always {@code [left, right]}. */
    @Override
    public List<PlanNode> children() {
        return List.of(left, right);
    }

    /** {@inheritDoc} For example {@code HASH_JOIN ON <condition>} or {@code HASH_JOIN NATURAL}. */
    @Override
    public String describe() {
        return algorithm + (condition == null ? " NATURAL" : " ON " + condition);
    }
}
