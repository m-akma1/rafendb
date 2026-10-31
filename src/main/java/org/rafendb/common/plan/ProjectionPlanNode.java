package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * Unary node that outputs the given expressions for each row of its child.
 *
 * <p>Immutable and thread-safe.
 *
 * @param columns the output expressions in order; copied to an unmodifiable list, not null and
 *     without null elements
 * @param child the input node, not null
 */
public record ProjectionPlanNode(List<Expr> columns, PlanNode child) implements PlanNode {

    /**
     * Checks the arguments and copies {@code columns}.
     *
     * @throws NullPointerException if an argument or an element of {@code columns} is null
     */
    public ProjectionPlanNode {
        columns = List.copyOf(columns);
        Objects.requireNonNull(child, "child");
    }

    /** {@inheritDoc} Always {@code [child]}. */
    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    /** {@inheritDoc} For example {@code PROJECTION [<expr>, <expr>]}. */
    @Override
    public String describe() {
        return "PROJECTION " + columns;
    }
}
