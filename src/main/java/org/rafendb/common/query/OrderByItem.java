package org.rafendb.common.query;

import org.rafendb.common.expr.Expr;

public record OrderByItem(Expr expression, boolean ascending) {}
