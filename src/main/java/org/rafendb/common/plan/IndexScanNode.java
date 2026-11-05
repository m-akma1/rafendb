package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;

/**
 * Leaf node that reads the records of a table found through an index.
 *
 * <p>Immutable and thread-safe.
 *
 * @param alias the alias written in the query, or {@code null} if none
 * @param table the table name, not null
 * @param descriptor which index to use and how, not null
 */
public record IndexScanNode(String alias, String table, IndexScanDescriptor descriptor)
        implements PlanNode {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if {@code table} or {@code descriptor} is null
     */
    public IndexScanNode {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(descriptor, "descriptor");
    }

    /** {@inheritDoc} Always empty. */
    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    /** {@inheritDoc} For example {@code INDEX SCAN student AS s USING idx_nim(nim)}. */
    @Override
    public String describe() {
        return "INDEX SCAN "
                + table
                + (alias == null ? "" : " AS " + alias)
                + " USING "
                + descriptor.describe();
    }
}
