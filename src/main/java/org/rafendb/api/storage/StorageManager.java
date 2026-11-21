package org.rafendb.api.storage;

import java.util.List;
import java.util.function.ToLongFunction;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.ReferencingForeignKey;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.ChangeRecord;

/**
 * Complete Storage Manager facade. Thread-safe. Query planning uses {@link StorageMetadata};
 * query execution uses {@link StorageQuery}; creating and dropping tables uses {@link
 * StorageDefinition}; recovery uses {@link StorageRecovery}. All views are implemented by the same
 * storage object.
 *
 * <p>All arguments and results are non-null unless stated; null arguments throw {@link
 * NullPointerException}. Names are passed already normalized. Returned lists are unmodifiable.
 * After {@link #close()}, all other methods throw {@link IllegalStateException}.
 *
 * <p>Errors:
 *
 * <ul>
 *   <li>{@link StorageRuntimeException}: the client's statement is wrong (unknown table, a row that
 *       does not fit the schema, a name in use, and so on). Thrown before any change.
 *   <li>{@link StorageInternalException}: a <em>storage failure</em> (disk I/O, corrupt data, or a
 *       log that cannot be forced) or a call that breaks this contract. Declared by every method
 *       that can touch disk.
 * </ul>
 *
 * <p>Writes take the LSN of an already created log record. A buffer write is not a COMMIT; see
 * {@link #flush()}. Isolation is CCM's job.
 */
public interface StorageManager
        extends StorageQuery, StorageDefinition, StorageRecovery, AutoCloseable {

    /**
     * Lists every foreign key that references {@code table}, from any table including itself.
     * Thread-safe.
     *
     * @param table the referenced table name, non-null
     * @return the foreign keys with their owning tables; unmodifiable, empty if none
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws StorageRuntimeException if the table does not exist
     */
    List<ReferencingForeignKey> listReferencingForeignKeys(String table);

    /**
     * Deletes every live record, keeping the table and indexes. For each record, {@code logDelete}
     * runs first on the calling thread to log the DELETE and return its LSN. If it throws, clearing
     * stops and the exception propagates; earlier deletions stay. The index entries of each deleted
     * record are removed as part of deleting that record, like {@link #delete}.
     *
     * @param table the table name
     * @param logDelete logs a record's DELETE and returns its LSN
     * @return the number of deleted records
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException on a storage failure; records deleted before it stay deleted
     */
    long clearTable(String table, ToLongFunction<StoredRow> logDelete)
            throws StorageInternalException;

    /**
     * Drops cached pages and reloads the catalog after a restore. The caller guarantees no active
     * transactions, cursors or pins.
     *
     * @throws StorageInternalException on a storage failure, including a restored catalog that
     *     cannot be read
     */
    void reloadAfterRestore() throws StorageInternalException;

    /**
     * Fills an empty database with sample tables (at least 3 related, at least 50 rows each, all
     * four types). Does nothing if tables already exist. An empty database requires the logging
     * callback overload; this method fails before creating tables rather than inventing WAL LSNs.
     *
     * @throws StorageInternalException if logging is not configured for an empty database
     */
    void loadSampleDatabase() throws StorageInternalException;

    /**
     * Loads the sample database with a logged INSERT for each row. Thread-safe; the caller must
     * provide exclusive bootstrap access and manage the transaction, commit and rollback. The
     * callback runs after RID reservation and before the row becomes visible, receives an INSERT
     * change with an encoded after-image, and returns its positive LSN. It may read storage but
     * must not modify it or mutate the image. An exception stops loading and cancels the
     * current reservation; earlier rows and DDL remain for the caller to handle. Does nothing if
     * tables already exist. A final storage flush obeys the injected WalSync.
     *
     * @param logInsert the INSERT logger, non-null; it must use the same WAL as this storage
     * @throws NullPointerException if logInsert is null
     * @throws StorageInternalException on a storage failure or a nonpositive callback LSN
     * @throws RuntimeException if the logging callback fails
     */
    void loadSampleDatabase(ToLongFunction<ChangeRecord> logInsert) throws StorageInternalException;

    /**
     * Flushes and releases all resources. Idempotent.
     *
     * @throws StorageInternalException if the final flush fails
     */
    @Override
    void close() throws StorageInternalException;
}
