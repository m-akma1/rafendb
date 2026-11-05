package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * Unary node that keeps only the rows of its child that satisfy a condition.
 *
 * <p>Immutable and thread-safe.
 *
 * @param condition the filter condition, not null
 * @param child the input node, not null
 */
public record SelectionPlanNode(Expr condition, PlanNode child) implements PlanNode {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if any argument is null
     */
    public SelectionPlanNode {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(child, "child");
    }

    /** {@inheritDoc} Always {@code [child]}. */
    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    /** {@inheritDoc} For example {@code SELECTION <condition>}. */
    @Override
    public String describe() {
        return "SELECTION " + condition;
    }
}
