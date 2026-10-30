package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Predicate;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record IndexScanNode(String table, String index, Predicate lookup) implements PlanNode {
    public IndexScanNode {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "IndexScanNode";
    }
}
