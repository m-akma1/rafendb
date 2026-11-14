package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.ColumnRef;

/** Expands the accepted SELECT-star representations against a resolved scope. */
final class StarExpander {
    private StarExpander() {}

    static boolean isStar(List<Expr> projection) {
        // TODO(contract): keep the accepted star encodings here as the parser contract settles.
        return projection.isEmpty()
                || projection.size() == 1
                        && projection.get(0) instanceof ColumnRef c
                        && c.column().equals("*");
    }

    static List<Expr> expand(List<Expr> projection, Scope scope, boolean natural) {
        if (!isStar(projection)) return projection;
        String qualifier = projection.isEmpty() ? null : ((ColumnRef) projection.get(0)).table();
        if (qualifier != null
                && scope.tables().stream().noneMatch(table -> Scope.eq(table.name(), qualifier)))
            throw new ValidationException("Table '" + qualifier + "' is not in FROM clause");
        List<Expr> result = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (Scope.Table table : scope.tables()) {
            if (qualifier != null && !Scope.eq(qualifier, table.name())) continue;
            for (var column : table.schema().columns()) {
                String key = column.name().toLowerCase(java.util.Locale.ROOT);
                if (!natural || emitted.add(key))
                    result.add(new ColumnRef(table.name(), column.name()));
            }
        }
        return List.copyOf(result);
    }
}
