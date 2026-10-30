package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.ColumnRef;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record ProjectionPlanNode(PlanNode input, List<ColumnRef> columns) implements PlanNode {
    public ProjectionPlanNode {
        Objects.requireNonNull(input, "input");
        columns = List.copyOf(columns);
    }

    @Override
    public List<PlanNode> children() {
        return List.of(input);
    }

    @Override
    public String describe() {
        return "ProjectionPlanNode";
    }
}
