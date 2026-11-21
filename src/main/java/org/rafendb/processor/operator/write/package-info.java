/**
 * DML operators: DELETE (QP-07), INSERT (QP-10), and UPDATE (QP-11). Each is a {@link
 * org.rafendb.processor.operator.write.DMLOperator} whose {@code execute()} returns the number of
 * affected rows.
 *
 * <p>Every operator runs inside the session's current transaction and never begins or commits one.
 * Per change, the order is: WRITE access to the table from CCM, prepare the change on SM (reserve a
 * Rid, encode the log images), log it through CCM to FRM, then apply it on SM with the log record's
 * LSN. A failure after a change was logged aborts the transaction. The shared steps live in {@code
 * WriteSupport}.
 *
 * <p>SM is used only through {@link org.rafendb.api.storage.StorageQuery}.
 *
 * <p>Owner: G1 Query Processor (QP3, QP5).
 */
package org.rafendb.processor.operator.write;
