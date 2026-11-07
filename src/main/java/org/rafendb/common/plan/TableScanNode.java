package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;

/**
 * Leaf node that reads every record of a table.
 *
 * <p>Immutable and thread-safe.
 *
 * @param alias the alias written in the query, or {@code null} if none
 * @param table the table name, not null
 */
public record TableScanNode(String alias, String table) implements PlanNode {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if {@code table} is null
     */
    public TableScanNode {
        Objects.requireNonNull(table, "table");
    }

    /** {@inheritDoc} Always empty. */
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    /** {@inheritDoc} For example {@code TABLE SCAN student AS s}. */
    @Override
    public String describe() {
        return "TABLE SCAN " + table + (alias == null ? "" : " AS " + alias);
    }
}
