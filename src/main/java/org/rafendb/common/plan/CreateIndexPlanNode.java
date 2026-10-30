package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.ColumnRef;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record CreateIndexPlanNode(String name, String table, ColumnRef column, IndexKind kind)
        implements PlanNode {
    public CreateIndexPlanNode {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(kind, "kind");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "CreateIndexPlanNode";
    }

    public enum IndexKind {
        BTREE,
        HASH
    }
}
