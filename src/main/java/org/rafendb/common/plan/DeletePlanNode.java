package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Predicate;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record DeletePlanNode(String table, java.util.Optional<Predicate> condition)
        implements PlanNode {
    public DeletePlanNode {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(condition, "condition");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "DeletePlanNode";
    }
}
