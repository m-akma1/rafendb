package org.rafendb.api.optimizer;

import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.error.OptimizerRuntimeException.SyntaxException;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;

/**
 * Entry point of the Query Optimizer (QO).
 *
 * <p>Pipeline: {@code parse → optimize} for SELECT, optionally followed by
 * {@code explain}. Other commands are parsed and validated without optimization.
 * Malformed SQL is reported via exceptions ({@code SyntaxException} or
 * {@code ValidationException}). Implementations are not required to be thread-safe.</p>
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
     * INSERT, DELETE, transaction commands, CREATE INDEX, CREATE TABLE, and DROP TABLE.
     * SELECT supports cartesian products, {@code JOIN ... ON}, {@code NATURAL JOIN},
     * table aliases with AS, and LIMIT. Expressions use one shared parser
     * across WHERE, JOIN ... ON, and SET (QO-01 through QO-06, QO-17).
     *
     * <p>Validation resolves schema-dependent information ({@code SELECT *} expansion,
     * shared columns of {@code NATURAL JOIN}) and checks table/column existence,
     * unqualified-column ambiguity, type compatibility, and INSERT/UPDATE value arity
     * and types (QO-07). On failure, the query is not forwarded to optimization.</p>
     *
     * @param sql the SQL text; must not be {@code null}
     * @return the bound and validated query tree
     * @throws SyntaxException if the query is syntactically invalid
     *         (misspelled keyword, incomplete clause, wrong clause order, unbalanced
     *         parentheses, unterminated string); the message names the offending token
     *         or position
     * @throws ValidationException if the query is semantically
     *         invalid; the message names the offending table or column
     */
    ParsedQuery parse(String sql);

    /**
     * Produces an optimized {@link QueryPlan} for a validated SELECT query by
     * generating candidate plans, estimating their cost from Storage Manager statistics,
     * and selecting the lowest-cost candidate. Applies selection and projection pushdown,
     * cartesian-to-join conversion, join reordering/regrouping, and index-scan selection.
     * Non-optimizable commands (transactions, CREATE INDEX, CREATE TABLE, DROP TABLE)
     * remain as validated {@link ParsedQuery} objects (QO-08 through QO-14).
     *
     * <p>The optional LIMIT remains on the bound SELECT rather than the plan.
     *
     * <p><b>Precondition:</b> {@code bound} must have passed
     * {@link #parse(String)} and must be a SELECT query.</p>
     *
     * @param bound a validated SELECT query; must not be {@code null}
     * @return the optimized SELECT plan
     * @throws UnsupportedOperationException if the statement is not a SELECT
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
