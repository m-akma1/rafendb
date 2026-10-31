package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * Describes sorting one input plan by a single column without executing the sort.
 *
 * <p>Instances are immutable and safe for concurrent reads.
 */
public final class SortPlanNode implements PlanNode {
    private final PlanNode child;
    private final Expr.ColumnRef column;
    private final Direction direction;

    /**
     * Stores a child plan and sort column with ascending direction.
     *
     * @param child the input child; must not be {@code null}
     * @param column the sort column; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public SortPlanNode(PlanNode child, Expr.ColumnRef column) {
        this(child, column, Direction.ASC);
    }

    /**
     * Stores a child plan, sort column, and explicit direction.
     *
     * @param child the input child, must not be {@code null}
     * @param column the sort column, must not be {@code null}
     * @param direction the sort direction, must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public SortPlanNode(PlanNode child, Expr.ColumnRef column, Direction direction) {
        this.child = Objects.requireNonNull(child, "child");
        this.column = Objects.requireNonNull(column, "column");
        this.direction = Objects.requireNonNull(direction, "direction");
    }

    /** @return the original input child, never {@code null} */
    public PlanNode child() {
        return child;
    }

    /** @return the original sort column, never {@code null} */
    public Expr.ColumnRef column() {
        return column;
    }

    /** @return the sort direction, never {@code null} */
    public Direction direction() {
        return direction;
    }

    /** {@inheritDoc} */
    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
        String key =
                column.table() == null ? column.column() : column.table() + "." + column.column();
        return "SORT " + key + " " + direction;
    }

    /** Direction of the single sort key. */
    public enum Direction {
        /** Ascending order. */
        ASC,
        /** Descending order. */
        DESC
    }
}
