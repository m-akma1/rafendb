package org.rafendb.api.storage;

import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.TableSchema;

/**
 * Table DDL: creating and dropping tables. Thread-safe. Used by the Query Processor only for
 * {@code CREATE TABLE} and {@code DROP TABLE}; everything else it does goes through {@link
 * StorageQuery}. Implemented by the same storage object as the other views of {@link
 * StorageManager}.
 *
 * <p>All arguments are non-null; null arguments throw {@link NullPointerException}. Names are
 * passed already normalized. After the owning storage manager is closed, all methods throw {@link
 * IllegalStateException}.
 */
public interface StorageDefinition {
    /**
     * Registers a new empty table in the catalog. DDL: not logged by the Failure Recovery Manager
     * and cannot be rolled back. Thread-safe.
     *
     * <p>A foreign key may reference the table itself. Its referenced columns must be exactly the
     * referenced table's primary key columns, in order, and each pair must have the same type
     * family (INTEGER, FLOAT, text).
     *
     * <p>Neither creating a table nor declaring a primary key creates any index; callers use
     * {@link StorageQuery#createIndex}.
     *
     * @param schema the table definition, non-null
     * @throws NullPointerException if {@code schema} is {@code null}
     * @throws StorageRuntimeException if the table exists, a referenced table does not exist, the
     *     referenced columns are not the referenced primary key in order, or the column counts or
     *     type families differ
     * @throws StorageInternalException if the catalog or the table file cannot be written
     */
    void createTable(TableSchema schema) throws StorageInternalException;

    /**
     * Removes a table's data, indexes and catalog entry. DDL: not logged by the Failure Recovery
     * Manager and cannot be undone. Thread-safe.
     *
     * <p>Self-referencing foreign keys are ignored. With {@code cascade}, the foreign keys of
     * other tables that reference this table are removed; those tables and their rows remain.
     *
     * <p>The table's indexes are removed too. A table later created with the same name starts
     * empty.
     *
     * @param table the table name, non-null
     * @param cascade whether to remove foreign keys of other tables that reference this table
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws StorageRuntimeException if the table does not exist, or {@code cascade} is {@code
     *     false} and another table has a foreign key referencing it
     * @throws StorageInternalException if the catalog cannot be written or the table files cannot
     *     be removed
     */
    void dropTable(String table, boolean cascade) throws StorageInternalException;
}
