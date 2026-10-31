package org.rafendb.api.storage;

import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.StoredRow;

/**
 * Forward-only lazy scan over the live records of a table, page by page. Cannot be rewound; call
 * {@link StorageManager#scan} again. Reserved and deleted slots are skipped. Concurrent changes do
 * not make it fail, but whether it sees them is unspecified (isolation is CCM's job). Must be
 * closed. Not thread-safe: owned by one thread.
 */
public interface RecordCursor extends AutoCloseable {
    /**
     * Returns the next live record; keeps returning {@code null} after the end.
     *
     * @return the next record, or {@code null} at the end
     * @throws IllegalStateException if closed
     * @throws StorageInternalException on a storage failure while reading the next page
     */
    StoredRow next() throws StorageInternalException;

    /** Releases pinned pages. Idempotent. */
    @Override
    void close();
}
