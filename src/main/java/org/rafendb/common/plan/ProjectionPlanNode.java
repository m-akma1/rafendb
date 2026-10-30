package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

public final class ProjectionPlanNode implements PlanNode {
    private final List<Expr> columns;
    private final PlanNode child;

    public ProjectionPlanNode(List<Expr> columns, PlanNode child) {
        columns = List.copyOf(columns);
        this.columns = columns;
        this.child = child;
    }

    public List<Expr> columns() {
        return columns;
    }

    public PlanNode child() {
        return child;
    }

    @Override
    public List<PlanNode> children() {
        return List.of(child);
    }

    @Override
    public String describe() {
        return "";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProjectionPlanNode that)) {
            return false;
        }
        return columns.equals(that.columns) && Objects.equals(child, that.child);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columns, child);
    }

    @Override
    public String toString() {
        return "ProjectionPlanNode[columns=" + columns + ", child=" + child + "]";
    }
}
