package org.rafendb.api.storage;

/**
 * Single exception type for Storage Manager errors: unknown table or index, schema mismatch, invalid
 * reservation, unsupported index operation, and I/O failure (as cause). Thrown before any data is
 * changed.
 */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception.
     *
     * @param message the detail message
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a cause.
     *
     * @param message the detail message
     * @param cause the cause, may be {@code null}
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
