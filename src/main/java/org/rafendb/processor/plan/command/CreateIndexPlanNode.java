package org.rafendb.processor.plan.command;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.PlanNode;

/** Node CREATE INDEX sementara QP2; target memakai Expr.ColumnRef bersama, bukan payload final QO. */
public record CreateIndexPlanNode(String name, String table, Expr.ColumnRef column, IndexKind kind)
        implements PlanNode {
    public CreateIndexPlanNode {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(column.column(), "column name");
        if (column.column().isBlank()) throw new IllegalArgumentException("Column name is blank");
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
