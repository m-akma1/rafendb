package org.rafendb.processor.operator.write;

/**
 * Defines the contract for Data Manipulation Language (DML) operators
 * that perform write operations on the database.
 *
 * <p>Unlike query operators that retrieve and process data through
 * an iterator-based execution model, DML operators perform operations
 * that modify database state, such as INSERT and UPDATE.</p>
 *
 * <p>Each implementation is responsible for executing its corresponding
 * write operation and returning the number of affected records.</p>
 */
public interface DMLOperator {
    /**
     * Executes the DML operation.
     *
     * <p>This method performs the corresponding database modification
     * and returns the number of records affected by the operation.</p>
     *
     * @return the number of records affected by the operation,
     *         or {@code null} if no result is available
     */
    public Long execute();
}
