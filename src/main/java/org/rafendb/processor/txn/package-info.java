/**
 * Transaction control and table locks for a session (QP-12). {@link
 * org.rafendb.processor.txn.TransactionCoordinator} runs BEGIN, COMMIT, and ABORT; {@link
 * org.rafendb.processor.txn.AutoCommit} runs a statement as its own transaction when no explicit
 * one is active; {@link org.rafendb.processor.txn.TableLocks} asks CCM for table locks on behalf of
 * operators, retrying while CCM answers WAITING and aborting the transaction when it answers
 * ABORTED.
 *
 * <p>Owner: G1 Query Processor (QP3, QP5).
 */
package org.rafendb.processor.txn;
