/**
 * Interfaces QP provides to its callers (server and driver): {@link
 * org.rafendb.api.processor.QueryProcessor} opens a {@link org.rafendb.api.processor.Session} per
 * client, and each session runs SQL and returns a {@link org.rafendb.common.query.QueryResult}
 * (QP-01, QP-02).
 *
 * <p>Owner: G1 Query Processor.
 */
package org.rafendb.api.processor;
