package org.rafendb.common.query;

import java.util.Objects;
import org.rafendb.common.schema.IndexType;

/**
 * {@code CREATE INDEX <name> ON <table>(<column>) [USING BTREE | HASH]} (QO-06). Not optimized.
 *
 * @param originalSql the original SQL text
 * @param indexName the name of the index to create
 * @param table the indexed table
 * @param column the indexed column
 * @param indexType the index structure; {@link IndexType#BTREE} when USING is omitted
 */
public record CreateIndexQuery(
        String originalSql, String indexName, String table, String column, IndexType indexType)
        implements ParsedQuery {
    /** Rejects {@code null} components. */
    public CreateIndexQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(indexType, "indexType");
    }
}
