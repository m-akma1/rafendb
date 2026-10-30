package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.expr.Expression;
import org.rafendb.common.expr.Predicate;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record UpdatePlanNode(
        String table, ColumnRef column, Expression value, java.util.Optional<Predicate> condition)
        implements PlanNode {
    public UpdatePlanNode {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(condition, "condition");
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "UpdatePlanNode";
    }
}
