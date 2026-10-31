package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record JoinPlanNode(PlanNode left, PlanNode right, Expr condition) implements PlanNode {
    public JoinPlanNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(condition, "condition");
    }

    @Override
    public List<PlanNode> children() {
        return List.of(left, right);
    }

    @Override
    public String describe() {
        return "JoinPlanNode";
    }
}
