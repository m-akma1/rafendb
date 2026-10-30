package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Predicate;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record SelectionPlanNode(PlanNode input, Predicate condition) implements PlanNode {
    public SelectionPlanNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(condition, "condition");
    }

    @Override
    public List<PlanNode> children() {
        return List.of(input);
    }

    @Override
    public String describe() {
        return "SelectionPlanNode";
    }
}
