package org.rafendb.common.query;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/** A parsed SELECT query. */
public record SelectQuery(
        String originalSql,
        List<Expr> projection,
        FromItem from,
        Expr whereCondition,
        List<OrderByItem> orderBy)
        implements ParsedQuery {

    public SelectQuery {
        Objects.requireNonNull(originalSql, "originalSql must not be null");
        Objects.requireNonNull(projection, "projection must not be null");
        Objects.requireNonNull(from, "from must not be null");
    }

    public List<String> sourceTables() {
        return from.tables();
    }
}
