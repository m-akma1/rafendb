package org.rafendb.common.query;

import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * {@code DELETE FROM <table> [WHERE <condition>]} (QO-05).
 *
 * @param originalSql the original SQL text
 * @param table the target table
 * @param where the filter condition, or {@code null} if there is no WHERE clause
 */
public record DeleteQuery(String originalSql, String table, Expr where) implements ParsedQuery {
    /** Rejects {@code null} for every component except {@code where}. */
    public DeleteQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(table, "table");
    }
}
