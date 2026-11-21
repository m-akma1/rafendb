/**
 * Exceptions that carry an execution failure from an operator to the client. {@link QueryExecutionException} wraps another component's failure in mid-execution, such as an SM disk error; {@link TransactionAbortedException} reports that CCM has aborted the transaction.
 *
 * <p>Owner: G1 Query Processor.
 */
package org.rafendb.processor.error;
