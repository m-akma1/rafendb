package org.rafendb.common.query;

/**
 * A query that has been parsed from raw SQL into a structured representation.
 *
 * <p>Concrete subtypes correspond to statement families: {@code SelectQuery},
 * {@code UpdateQuery}, {@code InsertQuery}, {@code DeleteQuery},
 * {@code TransactionQuery} (BEGIN/COMMIT/ABORT), and {@code CreateIndexQuery}.
 * Instances are produced by the Query Optimizer's parser and are immutable.</p>
 */
public interface ParsedQuery {

    /**
     * Returns the original SQL text from which this query was parsed.
     *
     * @return the original SQL string; never {@code null}
     */
    String originalSql();
}