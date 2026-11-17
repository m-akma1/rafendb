package org.rafendb.common.query;

import java.util.Objects;

public final class ExplainQuery implements ParsedQuery {
    private final String originalSql;
    private final SelectQuery selectQuery;

    public ExplainQuery(String originalSql, SelectQuery selectQuery) {
        this.originalSql = Objects.requireNonNull(originalSql, "originalSql");
        this.selectQuery = Objects.requireNonNull(selectQuery, "selectQuery");
    }

    public String originalSql() {
        return originalSql;
    }

    public SelectQuery selectQuery() {
        return selectQuery;
    }
}
