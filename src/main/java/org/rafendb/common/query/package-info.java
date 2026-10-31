/**
 * Parsed queries produced by QO's parser (QO-02 to QO-06): {@link
 * org.rafendb.common.query.ParsedQuery} and one record per statement family (SELECT, INSERT, UPDATE,
 * DELETE, transaction commands, CREATE INDEX). Also {@link org.rafendb.common.query.QueryResult},
 * the outcome QP returns for each executed statement (QP-01).
 *
 * <p>Owner: QO1, QP2; QueryResult: QP1.
 */
package org.rafendb.common.query;
