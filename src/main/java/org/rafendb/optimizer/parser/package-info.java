/**
 * Recursive-descent SQL parser (QO-02 to QO-06). {@link QueryParser} tokenizes the text and dispatches to {@code SelectParser} (SELECT, FROM and joins, ORDER BY, LIMIT), {@code DmlParser} (UPDATE, INSERT, DELETE), {@code CommandParser} (transaction commands, CREATE INDEX, CREATE TABLE, DROP TABLE) and the one {@code ExpressionParser} they share. It also accepts EXPLAIN in front of a SELECT. Parsing checks syntax only; resolving names against the catalog is the binder's job.
 *
 * <p>Owner: G3 Query Optimizer (QO1, QO2).
 */
package org.rafendb.optimizer.parser;
