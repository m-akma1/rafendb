package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

public final class SelectionPlanNode implements PlanNode {
    private final Expr condition;
    private final PlanNode child;

    public SelectionPlanNode(Expr condition, PlanNode child) {
        this.condition = condition;
        this.child = child;
    }

    public Expr condition() {
        return condition;
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
        if (!(other instanceof SelectionPlanNode that)) {
            return false;
        }
        return Objects.equals(condition, that.condition) && Objects.equals(child, that.child);
    }

    @Override
    public int hashCode() {
        return Objects.hash(condition, child);
    }

    @Override
    public String toString() {
        return "SelectionPlanNode[condition=" + condition + ", child=" + child + "]";
    }
}
