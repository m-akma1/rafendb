package org.rafendb.api.storage;

import java.util.List;
import java.util.Optional;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;

/**
 * Row and index operations used by query execution, plus storage metadata. Thread-safe.
 *
 * <p>All arguments and results are non-null unless stated; null arguments throw {@link
 * NullPointerException}. Names are passed already normalized. Returned lists are unmodifiable.
 * After the owning storage manager is closed, all methods throw {@link IllegalStateException}.
 *
 * <p>Writes take the LSN of an already created log record. A buffer write is not a COMMIT.
 * Isolation is CCM's job. Storage lifecycle and recovery belong to the owner and FRM.
 */
public interface StorageQuery extends StorageMetadata {

    /**
     * Encodes one row using the table's current schema. Thread-safe; does not change storage or
     * write a log. The returned array belongs to the caller. SQL NULL is encoded inside the image;
     * row absence is an empty Optional in the change record, never a null or empty image array.
     *
     * @param table the table name, non-null
     * @param row the row to encode, non-null
     * @return a new array in the binary format used by stored rows
     * @throws NullPointerException if an argument is null
     * @throws StorageRuntimeException if the table does not exist, the row does not fit its
     *     schema, text has invalid Unicode, or CHAR text ends in NUL (reserved for padding)
     */
    byte[] encodeRow(String table, Row row);

    /**
     * Opens a scan; the caller must close it.
     *
     * <p>If the table does not exist, this method throws {@link StorageRuntimeException}
     * immediately when called. It does not return an empty cursor and does not defer the error to
     * {@link RecordCursor#next()}.
     *
     * @param table the table name
     * @return a cursor positioned before the first record
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException on a storage failure while opening the scan
     */
    RecordCursor scan(String table) throws StorageInternalException;

    /**
     * Reads a record by address.
     *
     * @param table the table name
     * @param rid the address
     * @return the record, or empty if none is live there (deleted, unwritten or only reserved)
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException on a storage failure
     */
    Optional<StoredRow> fetch(String table, Rid rid) throws StorageInternalException;

    /**
     * Validates the row and reserves an invisible slot for it. INSERT order: this, the caller logs,
     * then {@link #insert}; on abandon, {@link #cancelReservation}.
     *
     * <p>The returned Rid (block, slot) is never one that was allocated before by this method or
     * by {@link StorageRecovery#applyRecovery}, even if its record was deleted or its reservation cancelled. The
     * space may be reused, but the slot number is not.
     *
     * @param table the table name
     * @param row the row to insert
     * @return the reserved address, never a previously allocated one
     * @throws StorageRuntimeException if the table does not exist or the row does not match the
     *     schema
     * @throws StorageInternalException on a storage failure
     */
    Rid reserveRid(String table, Row row) throws StorageInternalException;

    /**
     * Releases a pending reservation. Idempotent; does nothing if {@code rid} is not a pending
     * reservation, and never deletes a live record.
     *
     * <p>The cancelled Rid is not handed out again by {@link #reserveRid}. The space may be
     * reused, but the slot number is not.
     *
     * @param table the table name
     * @param rid the reserved address
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException on a storage failure
     */
    void cancelReservation(String table, Rid rid) throws StorageInternalException;

    /**
     * Stores the row in the reserved slot, makes it visible, and updates indexes and statistics.
     *
     * @param table the table name
     * @param rid the reserved address
     * @param row the row
     * @param lsn the INSERT log record's LSN
     * @return {@code rid}
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException if {@code rid} is not a pending reservation of the table,
     *     the row does not match the schema or fit the reserved capacity, or on a storage failure
     */
    Rid insert(String table, Rid rid, Row row, long lsn) throws StorageInternalException;

    /**
     * Replaces the whole record at {@code rid}, keeping the Rid. Updates changed indexes and
     * statistics.
     *
     * @param table the table name
     * @param rid the address of a live record
     * @param row the new row
     * @param lsn the UPDATE log record's LSN
     * @throws StorageRuntimeException if the table does not exist or the row does not match the
     *     schema
     * @throws StorageInternalException if no live record is at {@code rid}, the row cannot be
     *     stored at the same Rid (the caller may then log a DELETE and INSERT), or on a storage
     *     failure
     */
    void update(String table, Rid rid, Row row, long lsn) throws StorageInternalException;

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
     * @throws StorageRuntimeException if the table does not exist
     * @throws StorageInternalException on a storage failure
     */
    boolean delete(String table, Rid rid, long lsn) throws StorageInternalException;

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
     * @throws StorageRuntimeException if the table or column does not exist, the name is used, or a
     *     text column is too wide for the index structure
     * @throws StorageInternalException on a storage failure; no index is registered
     */
    void createIndex(IndexInfo index) throws StorageInternalException;

    /**
     * Finds records whose column equals {@code key}, which must be of the column's type family
     * (FLOAT column, FLOAT key); no conversion is done.
     *
     * @param index the index name
     * @param key the key
     * @return all matching Rids including duplicates, unordered; empty if none or {@code key} is
     *     NULL
     * @throws StorageInternalException if the index does not exist, the key is of another type
     *     family, or on a storage failure
     */
    List<Rid> lookupIndex(String index, Value key) throws StorageInternalException;

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
     * @throws StorageInternalException if the index does not exist, is not a B+ tree, a bound is
     *     of another type family, or on a storage failure
     */
    List<Rid> rangeIndex(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper)
            throws StorageInternalException;
}
