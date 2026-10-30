package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record TableScanNode(String table) implements PlanNode {
    public TableScanNode {
        Objects.requireNonNull(table, "table");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "TableScanNode";
    }
}
