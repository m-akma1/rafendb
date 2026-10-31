package org.rafendb.api.storage;

import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;
import org.rafendb.common.error.StorageException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.ReferencingForeignKey;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;

/**
 * The only entry point to the Storage Manager. Thread-safe.
 *
 * <p>All arguments and results are non-null unless stated; null arguments throw {@link
 * NullPointerException}. Names are passed already normalized. Returned lists are unmodifiable.
 * Errors (unknown table or index, I/O) are {@link StorageException}, thrown before any change.
 * After {@link #close()}, all other methods throw {@link IllegalStateException}.
 *
 * <p>Writes take the LSN of an already created log record. A buffer write is not a COMMIT; see
 * {@link #flush()}. Isolation is CCM's job.
 */
public interface StorageManager extends AutoCloseable {

    /**
     * Lists table names.
     *
     * @return the names, sorted
     */
    List<String> listTables();

    /**
     * Returns a table's schema, including its outgoing foreign keys.
     *
     * @param table the table name
     * @return the schema
     * @throws StorageException if the table does not exist
     */
    TableSchema getSchema(String table);

    /**
     * Lists every foreign key that references {@code table}, from any table including itself.
     * Thread-safe.
     *
     * @param table the referenced table name, non-null
     * @return the foreign keys with their owning tables; unmodifiable, empty if none
     * @throws NullPointerException if {@code table} is {@code null}
     * @throws StorageException if the table does not exist
     */
    List<ReferencingForeignKey> listReferencingForeignKeys(String table);

    /**
     * Registers a new empty table in the catalog. DDL: not logged by the Failure Recovery Manager
     * and cannot be rolled back. Thread-safe.
     *
     * <p>A foreign key may reference the table itself. Its referenced columns must be exactly the
     * referenced table's primary key columns, in order, and each pair must have the same type
     * family (INTEGER, FLOAT, text).
     *
     * <p>Neither creating a table nor declaring a primary key creates any index; callers use
     * {@link #createIndex}.
     *
     * @param schema the table definition, non-null
     * @throws NullPointerException if {@code schema} is {@code null}
     * @throws StorageException if the table exists, a referenced table does not exist, the
     *     referenced columns are not the referenced primary key in order, or the column counts or
     *     type families differ
     */
    void createTable(TableSchema schema);

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
     * @throws StorageException if the table does not exist, or {@code cascade} is {@code false}
     *     and another table has a foreign key referencing it
     */
    void dropTable(String table, boolean cascade);

    /**
     * Returns a table's statistics. Record and block counts are always current; the other fields
     * may be refreshed lazily.
     *
     * @param table the table name
     * @return the statistics
     * @throws StorageException if the table does not exist
     */
    TableStats getStats(String table);

    /**
     * Lists a table's indexes.
     *
     * @param table the table name
     * @return the indexes
     * @throws StorageException if the table does not exist
     */
    List<IndexInfo> listIndexes(String table);

    /**
     * Opens a scan; the caller must close it.
     *
     * <p>If the table does not exist, this method throws {@link StorageException} immediately when
     * called. It does not return an empty cursor and does not defer the error to {@link
     * RecordCursor#next()}.
     *
     * @param table the table name
     * @return a cursor positioned before the first record
     * @throws StorageException if the table does not exist
     */
    RecordCursor scan(String table);

    /**
     * Reads a record by address.
     *
     * @param table the table name
     * @param rid the address
     * @return the record, or empty if none is live there (deleted, unwritten or only reserved)
     * @throws StorageException if the table does not exist
     */
    Optional<StoredRow> fetch(String table, Rid rid);

    /**
     * Validates the row and reserves an invisible slot for it. INSERT order: this, the caller logs,
     * then {@link #insert}; on abandon, {@link #cancelReservation}.
     *
     * <p>The returned Rid (block, slot) is never one that was allocated before by this method or
     * by {@link #applyRecovery}, even if its record was deleted or its reservation cancelled. The
     * space may be reused, but the slot number is not.
     *
     * @param table the table name
     * @param row the row to insert
     * @return the reserved address, never a previously allocated one
     * @throws StorageException if the table does not exist or the row does not match the schema
     */
    Rid reserveRid(String table, Row row);

    /**
     * Releases a pending reservation. Idempotent; does nothing if {@code rid} is not a pending
     * reservation, and never deletes a live record.
     *
     * <p>The cancelled Rid is not handed out again by {@link #reserveRid}. The space may be
     * reused, but the slot number is not.
     *
     * @param table the table name
     * @param rid the reserved address
     * @throws StorageException if the table does not exist
     */
    void cancelReservation(String table, Rid rid);

    /**
     * Stores the row in the reserved slot, makes it visible, and updates indexes and statistics.
     *
     * @param table the table name
     * @param rid the reserved address
     * @param row the row
     * @param lsn the INSERT log record's LSN
     * @return {@code rid}
     * @throws StorageException if the table does not exist, {@code rid} is not a pending
     *     reservation of it, or the row does not match the schema or fit the reserved capacity
     */
    Rid insert(String table, Rid rid, Row row, long lsn);

    /**
     * Replaces the whole record at {@code rid}, keeping the Rid. Updates changed indexes and
     * statistics.
     *
     * @param table the table name
     * @param rid the address of a live record
     * @param row the new row
     * @param lsn the UPDATE log record's LSN
     * @throws StorageException if the table does not exist, no live record is at {@code rid}, the
     *     row does not match the schema, or it cannot be stored at the same Rid (the caller may
     *     then log a DELETE and INSERT)
     */
    void update(String table, Rid rid, Row row, long lsn);

    /**
     * Deletes the live record at {@code rid} and its index entries.
     *
     * <p>The Rid is not handed out again by {@link #reserveRid} afterwards. The space may be
     * reused, but the slot number is not.
     *
     * @param table the table name
     * @param rid the address
     * @param lsn the DELETE log record's LSN
     * @return {@code true} if a live record was deleted, {@code false} if none (deleted, unwritten
     *     or only reserved)
     * @throws StorageException if the table does not exist
     */
    boolean delete(String table, Rid rid, long lsn);

    /**
     * Deletes every live record, keeping the table and indexes. For each record, {@code logDelete}
     * runs first on the calling thread to log the DELETE and return its LSN. If it throws, clearing
     * stops and the exception propagates; earlier deletions stay. The index entries of each deleted
     * record are removed as part of deleting that record, like {@link #delete}.
     *
     * @param table the table name
     * @param logDelete logs a record's DELETE and returns its LSN
     * @return the number of deleted records
     * @throws StorageException if the table does not exist
     */
    long clearTable(String table, ToLongFunction<StoredRow> logDelete);

    /**
     * Builds an index from the existing records and registers it.
     *
     * <p>NULL values are not indexed: rows with a NULL key are never returned by {@link
     * #lookupIndex} or {@link #rangeIndex}, so IS NULL must not be answered through an index.
     *
     * <p>A text column too wide for the index structure is rejected. The B_PLUS_TREE limit is
     * currently 1337 UTF-8 bytes; HASH has no such limit. The limit is an implementation detail
     * and may change.
     *
     * <p>The build is atomic: if it fails, no index is registered and existing data is unchanged.
     *
     * @param index the index definition
     * @throws StorageException if the table or column does not exist, the name is used, or a text
     *     column is too wide for the index structure
     */
    void createIndex(IndexInfo index);

    /**
     * Finds records whose column equals {@code key}, which must be of the column's type family
     * (FLOAT column, FLOAT key); no conversion is done.
     *
     * @param index the index name
     * @param key the key
     * @return all matching Rids including duplicates, unordered; empty if none or {@code key} is
     *     NULL
     * @throws StorageException if the index does not exist or the key is of another type family
     */
    List<Rid> lookupIndex(String index, Value key);

    /**
     * Finds records whose column lies in a range, on a B+ tree index. Non-null bounds must be of
     * the column's type family; no conversion is done.
     *
     * @param index the index name
     * @param lower the lower bound, {@code null} if unbounded
     * @param includeLower whether {@code lower} matches
     * @param upper the upper bound, {@code null} if unbounded
     * @param includeUpper whether {@code upper} matches
     * @return matching Rids ordered by key, then Rid; empty if none or a bound is NULL
     * @throws StorageException if the index does not exist, is not a B+ tree, or a bound is of
     *     another type family
     */
    List<Rid> rangeIndex(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper);

    /**
     * Writes all dirty pages (after {@link WalSync#forceThrough}) and the catalog to disk. On
     * return all changes so far are durable. Used for FRM checkpoints.
     *
     * @throws StorageException on an I/O failure or if the log cannot be forced
     */
    void flush();

    /**
     * Applies an image for FRM undo and redo, without logging. With an image, the record at exactly
     * {@code rid} becomes it (created or overwritten); with empty, no record is there afterwards.
     * Allocates pages as needed, updates indexes and statistics. Idempotent.
     *
     * <p>Relies on {@link #reserveRid} never allocating a Rid again, even after its record was
     * deleted or its reservation cancelled. An undo can therefore restore a deleted record at its
     * original Rid even when other inserts happened in between. A Rid allocated here is likewise
     * never returned by {@link #reserveRid}.
     *
     * @param table the table name
     * @param rid the exact address
     * @param image the row to store, or empty to remove the record
     * @param lsn the LSN of the log record being undone or redone
     * @throws StorageException if the table does not exist or the image does not match the schema
     */
    void applyRecovery(String table, Rid rid, Optional<Row> image, long lsn);

    /**
     * Drops cached pages and reloads the catalog after a restore. The caller guarantees no active
     * transactions, cursors or pins.
     *
     * @throws StorageException on an I/O failure
     */
    void reloadAfterRestore();

    /**
     * Fills an empty database with sample tables (at least 3 related, at least 50 rows each, all
     * four types). Does nothing if tables already exist.
     *
     * @throws StorageException on an I/O failure
     */
    void loadSampleDatabase();

    /**
     * Flushes and releases all resources. Idempotent.
     *
     * @throws StorageException if the final flush fails
     */
    @Override
    void close();
}
