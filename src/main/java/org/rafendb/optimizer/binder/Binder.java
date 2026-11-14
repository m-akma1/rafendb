package org.rafendb.optimizer.binder;

import org.rafendb.common.query.ParsedQuery;

/**
 * Binds parsed SQL to schema metadata and performs semantic validation.
 *
 * <p>Implementations are thread-safe. Binding is deterministic and idempotent, so a facade may
 * bind once for validation and again before optimization, or retain the first result. Resolved
 * column references use canonical catalog names; SELECT stars are expanded into projection lists.
 *
 * <p>Binding also fills {@code ColumnRef.offset}, the position of the column in the input row of
 * the plan node that evaluates the expression. For a freshly built, unoptimized plan that row is
 * the concatenation of the FROM tables in FROM order (a join condition sees only its own join's
 * tables); optimization rules that change the column layout must re-offset. NATURAL JOINs in the
 * FROM tree are replaced by an explicit {@code Join} (or a {@code CartesianProduct} if the sides
 * share no column).
 */
public interface Binder {
    /**
     * Returns a validated query enriched with binding information.
     *
     * @param parsed non-null parsed query
     * @return non-null bound query
     * @throws org.rafendb.common.error.ValidationException if a catalog or type rule is violated
     */
    ParsedQuery bind(ParsedQuery parsed);
}
