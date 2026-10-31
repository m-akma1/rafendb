package org.rafendb.common.query;

import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * {@code UPDATE <table> SET <column> = <value> [WHERE <condition>]} (QO-05). Only one SET column is
 * supported.
 *
 * @param originalSql the original SQL text
 * @param table the target table
 * @param column the column assigned by SET
 * @param value the new value, an arithmetic expression that may reference columns
 * @param where the filter condition, or {@code null} if there is no WHERE clause
 */
public record UpdateQuery(String originalSql, String table, String column, Expr value, Expr where)
        implements ParsedQuery {
    /** Rejects {@code null} for every component except {@code where}. */
    public UpdateQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(value, "value");
    }
}
