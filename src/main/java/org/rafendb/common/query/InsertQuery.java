package org.rafendb.common.query;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/**
 * {@code INSERT INTO <table> VALUES (<v1>, <v2>, ...)} for a single row (QO-05).
 *
 * @param originalSql the original SQL text
 * @param table the target table
 * @param values the row values in written order; unmodifiable
 */
public record InsertQuery(String originalSql, String table, List<Expr> values)
        implements ParsedQuery {
    /** Copies {@code values} to make it unmodifiable and rejects {@code null} components. */
    public InsertQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(table, "table");
        values = List.copyOf(values);
    }
}
