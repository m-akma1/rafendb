package org.rafendb.processor.plan.command;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.plan.PlanNode;

/** Local QP command payload for dropping a table. */
public record DropTablePlanNode(String table, boolean cascade) implements PlanNode {
    public DropTablePlanNode {
        Objects.requireNonNull(table, "table");
        if (table.isBlank()) throw new IllegalArgumentException("table name must not be blank");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "DropTablePlanNode";
    }
}
