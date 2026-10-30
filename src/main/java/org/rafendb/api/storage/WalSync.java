package org.rafendb.api.storage;

/**
 * Forces the write-ahead log to disk. Implemented by FRM, given to the Storage Manager by the
 * engine at construction. Called with a page's highest LSN before that dirty page is written
 * (eviction and flush). Implementations must be thread-safe.
 */
@FunctionalInterface
public interface WalSync {
    /**
     * Forces the log; on return every record with LSN {@code <= lsn} is on stable storage.
     *
     * @param lsn the highest LSN that must be durable
     * @throws StorageException on failure
     */
    void forceThrough(long lsn);
}
