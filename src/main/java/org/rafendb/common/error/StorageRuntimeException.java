package org.rafendb.common.error;

/**
 * Storage Manager's {@link RafenDBRuntimeException}: an error in the client's statement, such as an
 * unknown table or a row that does not fit the schema. Thrown before any change.
 */
public class StorageRuntimeException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public StorageRuntimeException(String message) {
        super(Component.SM, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public StorageRuntimeException(String message, Throwable cause) {
        super(Component.SM, message, cause);
    }
}
