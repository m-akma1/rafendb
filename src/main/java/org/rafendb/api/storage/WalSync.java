package org.rafendb.api.storage;

import org.rafendb.common.error.RafenDBInternalException;

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
     * <p>If this throws, the page is not written and the Storage Manager throws a {@link
     * org.rafendb.common.error.StorageInternalException} with this as the cause.
     *
     * @param lsn the highest LSN that must be durable
     * @throws RafenDBInternalException if the log cannot be forced; FRM throws its own subclass
     */
    void forceThrough(long lsn) throws RafenDBInternalException;
}
