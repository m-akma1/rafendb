package org.rafendb.common.query;

import java.util.Objects;

public record ExplainQuery(String originalSql, SelectQuery selectQuery) implements ParsedQuery {
    public ExplainQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(selectQuery, "selectQuery");
    }
}
