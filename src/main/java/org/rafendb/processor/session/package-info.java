/**
 * Sessions and the Query Processor entry point (QP-02, QP-12). {@link QueryProcessorImpl} implements {@code api.processor} and opens one session per client. A session sends SQL through the optimizer, plans data-changing and DDL statements itself ({@code CommandPlanner}), runs the plan and maps the outcome to a query result ({@code ResultMapper}). It owns its transaction state: auto-commit outside BEGIN, and any error inside BEGIN rolls the transaction back.
 *
 * <p>Owner: G1 Query Processor (QP1).
 */
package org.rafendb.processor.session;
