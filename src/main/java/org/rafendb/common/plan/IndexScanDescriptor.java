package org.rafendb.common.plan;

import java.util.Objects;
import org.rafendb.common.expr.Expr;

// butuh IndexType dan IndexConditionType tapi nanti aja
public class IndexScanDescriptor {
    private final String indexName;
    private final String column;
    private final Expr condition;

    public IndexScanDescriptor(
            String indexName,
            String column,
            Expr condition) {
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(condition, "condition");
        this.indexName = indexName;
        this.column = column;
        this.condition = condition;
    }

    public String indexName() {
        return indexName;
    }

    public String column() {
        return column;
    }

    public Expr condition() {
        return condition;
    }

    public String describe() {
        return "";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IndexScanDescriptor that)) {
            return false;
        }
        return indexName.equals(that.indexName)
                && column.equals(that.column)
                && condition.equals(that.condition);
    }

    @Override
    public String toString() {
        return "IndexScanDescriptor[indexName="
                + indexName
                + ", column="
                + column
                // + ", indexType="
                // + indexType
                // + ", conditionType="
                // + conditionType
                + ", condition="
                + condition
                + "]";
    }
}
