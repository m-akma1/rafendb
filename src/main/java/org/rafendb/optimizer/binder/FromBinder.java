
package org.rafendb.optimizer.binder;

import java.util.HashSet;
import java.util.Set;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.FromItem;

/**
 * Binds a FROM tree: canonicalizes table names, binds every {@code JOIN ... ON} condition, and
 * turns each {@code NATURAL JOIN} into an explicit join.
 *
 * <p>A NATURAL JOIN with shared columns becomes {@link FromItem.Join} with the derived equality
 * condition; one without shared columns becomes {@link FromItem.CartesianProduct}, because a join
 * node always carries a condition. The duplicate columns it merges are reported through {@link
 * #merged()} so that {@code SELECT *} can drop them; the Project node above keeps the row shape.
 *
 * <p>Each condition is bound against the tables of its own join node only, so it cannot name a
 * table that is joined later, and its offsets are positions in that node's input row.
 */
final class FromBinder {
    private final Scope scope;
    private final Set<String> merged = new HashSet<>();

    FromBinder(Scope scope) {
        this.scope = scope;
    }

    /** Keys ({@code table.column}, lower case) merged away by NATURAL JOINs seen so far. */
    Set<String> merged() {
        return Set.copyOf(merged);
    }

    FromItem bind(FromItem item) {
        return switch (item) {
            case FromItem.TableRef ref -> new FromItem.TableRef(scope.table(ref.name()).name());
            case FromItem.CartesianProduct cp ->
                    new FromItem.CartesianProduct(bind(cp.left()), bind(cp.right()));
            case FromItem.Join join -> {
                FromItem left = bind(join.left());
                FromItem right = bind(join.right());
                Expr condition = conditionBinder(join).condition(join.condition(), "JOIN ... ON");
                yield new FromItem.Join(left, right, condition);
            }
            case FromItem.NaturalJoin natural -> {
                FromItem left = bind(natural.left());
                FromItem right = bind(natural.right());
                NaturalJoinResolver.Result result =
                        NaturalJoinResolver.resolve(
                                scope.withHidden(merged),
                                natural.left().tables(),
                                natural.right().tables());
                merged.addAll(result.merged());
                if (result.condition() == null) yield new FromItem.CartesianProduct(left, right);
                Expr condition = conditionBinder(natural).condition(result.condition(), "NATURAL JOIN");
                yield new FromItem.Join(left, right, condition);
            }
        };
    }

    private ExprBinder conditionBinder(FromItem node) {
        return new ExprBinder(scope.restrictTo(node.tables()).withHidden(merged));
    }
}
