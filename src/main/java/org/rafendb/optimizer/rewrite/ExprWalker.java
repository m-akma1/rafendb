package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.SelectQuery;

final class ExprWalker {

    private ExprWalker() {}

    static boolean containsSubquery(Expr expr) {
        if (expr instanceof Expr.Not not) return containsSubquery(not.child());
        if (expr instanceof Expr.Binary bin) {
            return containsSubquery(bin.left()) || containsSubquery(bin.right());
        }
        return expr instanceof Expr.ScalarSubquery
                || expr instanceof Expr.InSubquery
                || expr instanceof Expr.ExistsSubquery;
    }

    static boolean isCorrelated(Expr expr) {
        return !correlatedRefs(expr).isEmpty();
    }

    static List<Expr.ColumnRef> rowRefs(Expr expr) {
        List<Expr.ColumnRef> out = new ArrayList<>();
        map(
                expr,
                0,
                ref -> {
                    out.add(new Expr.ColumnRef(ref.table(), ref.column(), ref.offset(), 0));
                    return ref;
                });
        return out;
    }

    static List<Expr.ColumnRef> correlatedRefs(Expr expr) {
        List<Expr.ColumnRef> out = new ArrayList<>();
        collectCorrelated(expr, 0, false, out);
        return out;
    }

    static Expr mapRowRefs(Expr expr, UnaryOperator<Expr.ColumnRef> f) {
        return map(expr, 0, f);
    }

    private static Expr map(Expr expr, int level, UnaryOperator<Expr.ColumnRef> f) {
        if (expr instanceof Expr.ColumnRef ref) {
            return ref.scopeDepth() == level ? f.apply(ref) : ref;
        }
        if (expr instanceof Expr.Binary bin) {
            Expr left = map(bin.left(), level, f);
            Expr right = map(bin.right(), level, f);
            return left == bin.left() && right == bin.right()
                    ? bin
                    : new Expr.Binary(bin.op(), left, right);
        }
        if (expr instanceof Expr.Not not) {
            Expr child = map(not.child(), level, f);
            return child == not.child() ? not : new Expr.Not(child);
        }
        if (expr instanceof Expr.ScalarSubquery sub) {
            return new Expr.ScalarSubquery(mapSelect(sub.query(), level + 1, f));
        }
        if (expr instanceof Expr.ExistsSubquery sub) {
            return new Expr.ExistsSubquery(mapSelect(sub.query(), level + 1, f));
        }
        if (expr instanceof Expr.InSubquery sub) {
            return new Expr.InSubquery(
                    map(sub.value(), level, f), mapSelect(sub.query(), level + 1, f));
        }
        return expr;
    }

    private static SelectQuery mapSelect(
            SelectQuery query, int level, UnaryOperator<Expr.ColumnRef> f) {
        List<Expr> projection = new ArrayList<>();
        for (Expr expr : query.projection()) projection.add(map(expr, level, f));
        List<OrderByItem> orderBy = new ArrayList<>();
        for (OrderByItem item : query.orderBy()) {
            orderBy.add(new OrderByItem(map(item.expression(), level, f), item.ascending()));
        }
        return new SelectQuery(
                query.originalSql(),
                projection,
                mapFrom(query.from(), level, f),
                query.whereCondition() == null ? null : map(query.whereCondition(), level, f),
                orderBy,
                query.limit());
    }

    private static FromItem mapFrom(FromItem from, int level, UnaryOperator<Expr.ColumnRef> f) {
        return switch (from) {
            case FromItem.TableRef table -> table;
            case FromItem.CartesianProduct product ->
                    new FromItem.CartesianProduct(
                            mapFrom(product.left(), level, f), mapFrom(product.right(), level, f));
            case FromItem.Join join ->
                    new FromItem.Join(
                            mapFrom(join.left(), level, f),
                            mapFrom(join.right(), level, f),
                            map(join.condition(), level, f));
            case FromItem.NaturalJoin natural ->
                    new FromItem.NaturalJoin(
                            mapFrom(natural.left(), level, f), mapFrom(natural.right(), level, f));
        };
    }

    private static void collectCorrelated(
            Expr expr, int level, boolean inside, List<Expr.ColumnRef> out) {
        if (expr instanceof Expr.ColumnRef ref) {
            if (inside && ref.scopeDepth() == level) {
                out.add(new Expr.ColumnRef(ref.table(), ref.column(), ref.offset(), 0));
            }
        } else if (expr instanceof Expr.Binary bin) {
            collectCorrelated(bin.left(), level, inside, out);
            collectCorrelated(bin.right(), level, inside, out);
        } else if (expr instanceof Expr.Not not) {
            collectCorrelated(not.child(), level, inside, out);
        } else if (expr instanceof Expr.ScalarSubquery sub) {
            collectCorrelatedSelect(sub.query(), level + 1, out);
        } else if (expr instanceof Expr.ExistsSubquery sub) {
            collectCorrelatedSelect(sub.query(), level + 1, out);
        } else if (expr instanceof Expr.InSubquery sub) {
            collectCorrelated(sub.value(), level, inside, out);
            collectCorrelatedSelect(sub.query(), level + 1, out);
        }
    }

    private static void collectCorrelatedSelect(
            SelectQuery query, int level, List<Expr.ColumnRef> out) {
        for (Expr expr : query.projection()) collectCorrelated(expr, level, true, out);
        for (OrderByItem item : query.orderBy()) {
            collectCorrelated(item.expression(), level, true, out);
        }
        if (query.whereCondition() != null) {
            collectCorrelated(query.whereCondition(), level, true, out);
        }
        collectCorrelatedFrom(query.from(), level, out);
    }

    private static void collectCorrelatedFrom(FromItem from, int level, List<Expr.ColumnRef> out) {
        switch (from) {
            case FromItem.TableRef _ -> {}
            case FromItem.CartesianProduct product -> {
                collectCorrelatedFrom(product.left(), level, out);
                collectCorrelatedFrom(product.right(), level, out);
            }
            case FromItem.Join join -> {
                collectCorrelatedFrom(join.left(), level, out);
                collectCorrelatedFrom(join.right(), level, out);
                collectCorrelated(join.condition(), level, true, out);
            }
            case FromItem.NaturalJoin natural -> {
                collectCorrelatedFrom(natural.left(), level, out);
                collectCorrelatedFrom(natural.right(), level, out);
            }
        }
    }
}
