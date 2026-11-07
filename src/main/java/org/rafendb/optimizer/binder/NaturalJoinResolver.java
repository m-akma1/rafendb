package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.error.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.Binary;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.expr.Expr.Op;

/** Builds equality predicates for identically named columns in natural joins. */
final class NaturalJoinResolver {
    private NaturalJoinResolver() {}

    static Expr resolve(Scope scope) {
        List<Expr> terms = new ArrayList<>();
        for (int i = 1; i < scope.tables().size(); i++) {
            Scope.Table right = scope.tables().get(i);
            for (var rc : right.schema().columns()) {
                for (int j = 0; j < i; j++) {
                    Scope.Table left = scope.tables().get(j);
                    var matching =
                            left.schema().columns().stream()
                                    .filter(column -> Scope.eq(column.name(), rc.name()))
                                    .findFirst();
                    if (matching.isPresent()) {
                        var lc = matching.get();
                        if (lc.type().isNumeric() != rc.type().isNumeric()
                                || lc.type().isText() != rc.type().isText())
                            throw new ValidationException(
                                    "NATURAL JOIN column '"
                                            + left.name()
                                            + "."
                                            + lc.name()
                                            + "' and '"
                                            + right.name()
                                            + "."
                                            + rc.name()
                                            + "' have incompatible types "
                                            + lc.type()
                                            + " and "
                                            + rc.type());
                        terms.add(
                                new Binary(
                                        Op.EQ,
                                        new ColumnRef(left.name(), lc.name()),
                                        new ColumnRef(right.name(), rc.name())));
                        break;
                    }
                }
            }
        }
        Expr result = null;
        for (Expr term : terms) result = result == null ? term : new Binary(Op.AND, result, term);
        return result;
    }
}
