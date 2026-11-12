package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.Binary;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.expr.Expr.Op;
import org.rafendb.common.schema.Column;

/**
 * Derives the equality condition of one {@code left NATURAL JOIN right} node from the catalog.
 *
 * <p>Every column of the right side whose name also appears among the visible columns of the left
 * side becomes {@code left.col = right.col}; the right-hand copy is then hidden from {@code SELECT
 * *}. Columns already merged by an earlier NATURAL JOIN on the left count once, through their
 * surviving copy.
 */
final class NaturalJoinResolver {
    private NaturalJoinResolver() {}

    /**
     * @param condition the AND chain of equalities, or {@code null} if no column is shared
     * @param merged {@code table.column} keys (lower case) of the right-hand copies to hide
     */
    record Result(Expr condition, Set<String> merged) {}

    /**
     * @param scope the scope that holds both sides; must contain every table named below
     * @param leftTables tables of the left subtree, in FROM order
     * @param rightTables tables of the right subtree, in FROM order
     * @throws ValidationException if a shared column is ambiguous on the left or its types are
     *     incompatible
     */
    static Result resolve(Scope scope, List<String> leftTables, List<String> rightTables) {
        List<Expr> terms = new ArrayList<>();
        Set<String> merged = new HashSet<>();
        for (String rightName : rightTables) {
            Scope.Table right = scope.table(rightName);
            for (Column rc : right.schema().columns()) {
                if (scope.isHidden(right, rc)) continue;
                Scope.Table matchTable = null;
                Column match = null;
                for (String leftName : leftTables) {
                    Scope.Table left = scope.table(leftName);
                    for (Column lc : left.schema().columns()) {
                        if (scope.isHidden(left, lc) || !Scope.eq(lc.name(), rc.name())) continue;
                        if (match != null)
                            throw new ValidationException(
                                    "Ambiguous column '"
                                            + rc.name()
                                            + "' in NATURAL JOIN: found in both '"
                                            + matchTable.name()
                                            + "' and '"
                                            + left.name()
                                            + "'");
                        matchTable = left;
                        match = lc;
                    }
                }
                if (match == null) continue;
                if (match.type().isNumeric() != rc.type().isNumeric()
                        || match.type().isText() != rc.type().isText())
                    throw new ValidationException(
                            "NATURAL JOIN column '"
                                    + matchTable.name()
                                    + "."
                                    + match.name()
                                    + "' and '"
                                    + right.name()
                                    + "."
                                    + rc.name()
                                    + "' have incompatible types "
                                    + match.type()
                                    + " and "
                                    + rc.type());
                terms.add(
                        new Binary(
                                Op.EQ,
                                new ColumnRef(matchTable.name(), match.name()),
                                new ColumnRef(right.name(), rc.name())));
                merged.add(Scope.key(right.name(), rc.name()));
            }
        }
        Expr result = null;
        for (Expr term : terms) result = result == null ? term : new Binary(Op.AND, result, term);
        return new Result(result, merged);
    }
}
