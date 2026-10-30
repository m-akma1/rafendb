package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record CartesianProductPlanNode(PlanNode left, PlanNode right) implements PlanNode {
    public CartesianProductPlanNode {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
    }

    @Override
    public List<PlanNode> children() {
        return List.of(left, right);
    }

    @Override
    public String describe() {
        return "CartesianProductPlanNode";
    }
}
