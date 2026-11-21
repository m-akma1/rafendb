package org.rafendb.processor.plan.command;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.schema.TableSchema;

/** Local QP command payload for creating a table. */
public record CreateTablePlanNode(TableSchema schema) implements PlanNode {
    public CreateTablePlanNode {
        Objects.requireNonNull(schema, "schema");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "CreateTablePlanNode";
    }
}
