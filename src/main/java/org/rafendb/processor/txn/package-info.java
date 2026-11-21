/**
 * Table locks for a session's statements (QP-12). {@link org.rafendb.processor.txn.TableLocks} asks
 * CCM for table locks on behalf of operators, retrying while CCM answers WAITING and aborting the
 * transaction when it answers ABORTED. BEGIN, COMMIT, ABORT, and auto-commit belong to the session
 * ({@code org.rafendb.processor.session}), which is the only writer of its transaction state.
 *
 * <p>Owner: G1 Query Processor (QP3, QP5).
 */
package org.rafendb.processor.txn;
