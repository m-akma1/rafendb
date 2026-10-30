package org.rafendb.mock.optimizer;

import org.rafendb.common.query.ParsedQuery;

/**
 * Minimal {@link ParsedQuery} that just echoes the original SQL.
 * Replace with real query subtypes once parsing is implemented.
 */
public final class PlaceholderParsedQuery implements ParsedQuery {
    private final String sql;

    PlaceholderParsedQuery(String sql) {
        this.sql = sql;
    }

    @Override
    public String originalSql() {
        return sql;
    }

    @Override
    public String toString() {
        return "PlaceholderParsedQuery{" + sql + "}";
    }
}
