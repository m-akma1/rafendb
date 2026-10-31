package org.rafendb.api.recovery;

/**
 * Log flusher contract consumed by storage and buffer managers.
 *
 * <p>Guarantees Write-Ahead Logging (WAL) constraints before pages are flushed to disk.
 */
public interface ILogFlusher {

    /**
     * Forces log records up to the specified LSN to persistent storage.
     *
     * @param lsn the log sequence number boundary
     * @throws LoggerErrorException if flushing fails
     */
    void flushLogUntil(long lsn) throws LoggerErrorException;
}
