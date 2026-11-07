package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/** Node UPDATE sementara QP2; target memakai Expr.ColumnRef bersama, bukan payload final QO. */
public record UpdatePlanNode(
        String table, Expr.ColumnRef column, Expr value, java.util.Optional<Expr> condition)
        implements PlanNode {
    public UpdatePlanNode {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(column.column(), "column name");
        if (column.column().isBlank()) throw new IllegalArgumentException("Column name is blank");
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
