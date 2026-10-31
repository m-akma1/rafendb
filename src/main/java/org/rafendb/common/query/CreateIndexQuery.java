package org.rafendb.common.query;

import java.util.Objects;

/**
 * {@code CREATE INDEX <name> ON <table>(<column>)} (QO-06). Not optimized. The syntax for choosing
 * an index type is still open and is not represented here.
 *
 * @param originalSql the original SQL text
 * @param indexName the name of the index to create
 * @param table the indexed table
 * @param column the indexed column
 */
public record CreateIndexQuery(String originalSql, String indexName, String table, String column)
        implements ParsedQuery {
    /** Rejects {@code null} components. */
    public CreateIndexQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
    }
}
