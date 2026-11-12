package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.ColumnRef;

/** Expands the accepted SELECT-star representations against a resolved scope. */
final class StarExpander {
    private StarExpander() {}

    /** An empty projection or a lone {@code *} / {@code t.*} means "all columns". */
    static boolean isStar(List<Expr> projection) {
        // TODO(contract): keep the accepted star encodings here as the parser contract settles.
        return projection.isEmpty() || projection.size() == 1 && isStarItem(projection.get(0));
    }

    private static boolean isStarItem(Expr expr) {
        return expr instanceof ColumnRef c && c.column().equals("*");
    }

    /**
     * Replaces every {@code *} or {@code t.*} item by explicit columns, keeping the order of the
     * other items. A bare {@code *} skips columns merged away by NATURAL JOIN; {@code t.*} keeps
     * all columns of {@code t}.
     */
    static List<Expr> expand(List<Expr> projection, Scope scope) {
        if (projection.isEmpty()) return expandItem(null, scope);
        List<Expr> result = new ArrayList<>();
        for (Expr item : projection) {
            if (isStarItem(item)) result.addAll(expandItem(((ColumnRef) item).table(), scope));
            else result.add(item);
        }
        return List.copyOf(result);
    }

    private static List<Expr> expandItem(String qualifier, Scope scope) {
        if (qualifier != null && scope.table(qualifier) == null)
            throw new ValidationException("Table '" + qualifier + "' is not in FROM clause");
        List<Expr> result = new ArrayList<>();
        for (Scope.Table table : scope.tables()) {
            if (qualifier != null && !Scope.eq(qualifier, table.name())) continue;
            for (var column : table.schema().columns()) {
                if (qualifier == null && scope.isHidden(table, column)) continue;
                result.add(new ColumnRef(table.name(), column.name()));
            }
        }
        return result;
    }
}
