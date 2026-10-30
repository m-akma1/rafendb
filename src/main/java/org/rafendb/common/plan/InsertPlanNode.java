package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.type.Value;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record InsertPlanNode(String table, List<Value> values) implements PlanNode {
    public InsertPlanNode {
        Objects.requireNonNull(table, "table");
        values = List.copyOf(values);
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "InsertPlanNode";
    }
}
