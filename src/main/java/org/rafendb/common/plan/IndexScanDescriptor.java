package org.rafendb.common.plan;

import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * How an {@link IndexScanNode} uses an index: which index, on which column, with which condition.
 *
 * <p>Immutable and thread-safe.
 *
 * @param indexName the index name, not null
 * @param column the indexed column, not null
 * @param condition the condition the index answers, not null
 */
public record IndexScanDescriptor(String indexName, String column, Expr condition) {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if any argument is null
     */
    public IndexScanDescriptor {
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(condition, "condition");
    }

    /**
     * Returns a short description for plan display, for example {@code idx_nim(nim)}.
     *
     * @return the description, never null
     */
    public String describe() {
        return indexName + "(" + column + ")";
    }
}
