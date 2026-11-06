package org.rafendb.storage.index;

import java.util.List;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.Value;

/**
 * One index structure over one column, stored in pages. Holds (key, Rid) entries; adding an entry
 * that exists or removing one that does not changes nothing and dirties no page, so operations can
 * safely be repeated during recovery.
 *
 * <p>Keys and bounds passed here have already passed {@link IndexKeys#normalize}: they are non-NULL
 * and of the column's type family. All results are unmodifiable.
 *
 * <p>Not thread-safe: the caller holds a read lock around {@link #lookup} and {@link #range}, and a
 * write lock around everything else. All state is in the file; nothing is cached between calls.
 */
public interface Index {

    /**
     * Formats an empty file as an empty index, without a log record.
     *
     * @throws StorageInternalException if the file already has pages or on an I/O failure
     */
    void initialize() throws StorageInternalException;

    /**
     * Adds an entry as part of a logged operation.
     *
     * @param key the key
     * @param rid the record's address
     * @param lsn the LSN of the operation's log record
     * @return {@code true} if added, {@code false} if the entry was already there
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    boolean insert(Value key, Rid rid, long lsn) throws StorageInternalException;

    /**
     * Adds an entry while building the index from its table, without a log record.
     *
     * @param key the key
     * @param rid the record's address
     * @return {@code true} if added, {@code false} if the entry was already there
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    boolean insertUnlogged(Value key, Rid rid) throws StorageInternalException;

    /**
     * Removes an entry as part of a logged operation, leaving other records with the same key.
     *
     * @param key the key
     * @param rid the record's address
     * @param lsn the LSN of the operation's log record
     * @return {@code true} if removed, {@code false} if the entry was not there
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    boolean delete(Value key, Rid rid, long lsn) throws StorageInternalException;

    /**
     * Finds the records with a key.
     *
     * @param key the key
     * @return the matching Rids; empty if none
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     */
    List<Rid> lookup(Value key) throws StorageInternalException;

    /**
     * Finds the records whose key lies in a range.
     *
     * @param lower the lower bound, {@code null} if unbounded
     * @param includeLower whether {@code lower} itself matches
     * @param upper the upper bound, {@code null} if unbounded
     * @param includeUpper whether {@code upper} itself matches
     * @return the matching Rids ordered by key, then Rid; empty if none
     * @throws StorageInternalException if the file is not a valid index or on an I/O failure
     * @throws UnsupportedOperationException if this structure cannot search ranges
     */
    List<Rid> range(Value lower, boolean includeLower, Value upper, boolean includeUpper)
            throws StorageInternalException;
}
