package org.rafendb.common.query;

import java.util.List;
import org.rafendb.common.expr.Expr;

public record SelectQuery(
        String originalSql,
        List<Expr> projection,
        List<String> sourceTables,
        Expr joinCondition,
        Expr whereCondition)
        implements ParsedQuery {}
