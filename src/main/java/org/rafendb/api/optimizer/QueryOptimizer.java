package org.rafendb.api.optimizer;

import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;

/**
 * Entry point of the Query Optimizer (QO).
 *
 * <p>Pipeline: {@code parse → optimize}, optionally followed by
 * {@code explain}. Never crashes on malformed input; failures are reported via
 * exceptions ({@code SyntaxException} or {@code ValidationException}). Implementations are not required to be thread-safe.</p>
 *
 * @see ParsedQuery
 * @see QueryPlan
 */
public interface QueryOptimizer {

    /**
     * Parses and validates a raw SQL string into a bound {@link ParsedQuery}.
     *
     * <p>Keywords are case-insensitive; whitespace between tokens is ignored. Recognizes
     * identifiers (incl. {@code table.column}), numeric and quoted-string literals,
     * comparison and arithmetic operators, and punctuation. Supports SELECT, UPDATE,
     * INSERT, DELETE, transaction commands, and CREATE INDEX, including cartesian product,
     * {@code JOIN ... ON}, and {@code NATURAL JOIN}. Expressions use one shared parser
     * across WHERE, JOIN ... ON, and SET (QO-01 through QO-06).
     *
     * <p>Validation resolves schema-dependent information ({@code SELECT *} expansion,
     * shared columns of {@code NATURAL JOIN}) and checks table/column existence,
     * unqualified-column ambiguity, type compatibility, and INSERT/UPDATE value arity
     * and types (QO-07). On failure, the query is not forwarded to optimization.</p>
     *
     * @param sql the SQL text; must not be {@code null}
     * @return the bound and validated query tree
     * @throws org.rafendb.common.error.SyntaxException if the query is syntactically invalid
     *         (misspelled keyword, incomplete clause, wrong clause order, unbalanced
     *         parentheses, unterminated string); the message names the offending token
     *         or position
     * @throws org.rafendb.common.error.ValidationException if the query is semantically
     *         invalid; the message names the offending table or column
     */
    ParsedQuery parse(String sql);

    /**
     * Produces the cheapest executable {@link QueryPlan} for a validated query by
     * generating candidate plans, estimating their cost from Storage Manager statistics,
     * and selecting the lowest-cost candidate. Applies selection and projection pushdown,
     * cartesian-to-join conversion, join reordering/regrouping, and index-scan selection.
     * Non-optimizable commands (transactions, CREATE INDEX) are returned as structured
     * plans without optimization (QO-08 through QO-14).
     *
     * <p><b>Precondition:</b> {@code parsed} must have passed
     * {@link #parse(String)}.</p>
     *
     * @param bound a validated parsed query; must not be {@code null}
     * @return the cheapest executable plan
     * @throws OptimizerInternalException if an error occurs during optimization
     */
    QueryPlan optimize(ParsedQuery bound) throws OptimizerInternalException;

    /**
     * Renders a plan as an indented, human-readable tree showing each node's operation
     * and parameters, plus the estimated total cost (QO-15).
     *
     * @param plan the plan to render; must not be {@code null}
     * @return the textual representation of the plan
     */
    String explain(QueryPlan plan);
}
