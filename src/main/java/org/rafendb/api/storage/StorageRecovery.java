package org.rafendb.api.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;

/**
 * Storage operations needed to log table identity, undo, redo and checkpoint. Thread-safe.
 *
 * <p>Arguments and results are non-null; null arguments throw {@link NullPointerException}.
 * Names are passed already normalized. Returned lists are unmodifiable. After the owning storage
 * manager is closed, all methods throw {@link IllegalStateException}. Closing storage remains the
 * owner's responsibility.
 */
public interface StorageRecovery {
    /**
     * Lists table names.
     *
     * @return the names, sorted
     */
    List<String> listTables();

    /**
     * Returns the persistent identity of this incarnation of a table. The identity survives
     * restart and changes after DROP followed by CREATE, even with the same table name. FRM must
     * record it with row changes and compare it before decoding or replaying old images.
     *
     * @param table the table name, non-null
     * @return the table generation, non-null
     * @throws NullPointerException if table is null
     * @throws StorageRuntimeException if the table does not exist
     */
    UUID getTableGeneration(String table);

    /**
     * Decodes exactly one complete row image with the schema used to encode it. Does not change
     * storage or the image. The caller must not modify the image during this call. FRM must check
     * the table generation before decoding a historical image.
     *
     * @param table the table name, non-null
     * @param image the encoded row, non-null; SQL NULL values are inside this image
     * @return the decoded row, non-null
     * @throws NullPointerException if an argument is null
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException if the image is malformed, truncated, has trailing bytes,
     *     or does not fit the schema
     */
    Row decodeRow(String table, byte[] image) throws StorageInternalException;

    /**
     * Applies an image for FRM undo and redo, without logging. With an image, the record at exactly
     * {@code rid} becomes it (created or overwritten); with empty, no record is there afterwards.
     * Allocates pages as needed, updates indexes and statistics. Idempotent.
     *
     * <p>Relies on {@link StorageQuery#reserveRid} never allocating a Rid again, even after its
     * record was deleted or its reservation cancelled. An undo can therefore restore a deleted
     * record at its original Rid even when other inserts happened in between. A Rid allocated here
     * is likewise never returned by {@link StorageQuery#reserveRid}.
     *
     * @param table the table name
     * @param rid the exact address
     * @param image the row to store, or empty to remove the record
     * @param lsn the LSN of the log record being undone or redone
     * @throws StorageInternalException if the table does not exist, the image does not match the
     *     schema, or on a storage failure
     */
    void applyRecovery(String table, Rid rid, Optional<Row> image, long lsn)
            throws StorageInternalException;

    /**
     * Writes all dirty pages (after {@link WalSync#forceThrough}) and the catalog to disk. On
     * return all changes so far are durable. Used for FRM checkpoints.
     *
     * @throws StorageInternalException on a storage failure; if the log cannot be forced, the
     *     {@link WalSync} failure is the cause
     */
    void flush() throws StorageInternalException;
}
