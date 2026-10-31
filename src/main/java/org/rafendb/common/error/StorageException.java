package org.rafendb.common.error;

/**
 * The single unchecked exception type thrown by the Storage Manager to its callers: unknown table
 * or index, a row that does not match the schema, an invalid reservation, an unsupported index
 * operation, and I/O failures (with the {@link java.io.IOException} as cause). Callers such as QP
 * catch it and report the error to the user.
 */
public class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause, for example an {@link java.io.IOException}
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
