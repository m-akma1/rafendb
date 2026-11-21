package org.rafendb.common.query;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;

/** A SELECT query. An absent LIMIT has a null count, while zero requests an empty result. */
public record SelectQuery(
        String originalSql,
        List<Expr> projection,
        FromItem from,
        Expr whereCondition,
        List<OrderByItem> orderBy,
        Integer limit)
        implements ParsedQuery {

    public SelectQuery {
        Objects.requireNonNull(originalSql, "originalSql must not be null");
        Objects.requireNonNull(projection, "projection must not be null");
        Objects.requireNonNull(from, "from must not be null");
        if (limit != null && limit < 0) throw new IllegalArgumentException("negative LIMIT");
    }

    public SelectQuery(
            String originalSql,
            List<Expr> projection,
            FromItem from,
            Expr whereCondition,
            List<OrderByItem> orderBy) {
        this(originalSql, projection, from, whereCondition, orderBy, null);
    }

    public List<String> sourceTables() {
        return from.tables();
    }
}
