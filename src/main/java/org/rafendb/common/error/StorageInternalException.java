package org.rafendb.common.error;

/**
 * Storage Manager's {@link RafenDBInternalException}: a storage failure (disk I/O, corrupt data, or
 * a log that cannot be forced) or a call that breaks the {@link
 * org.rafendb.api.storage.StorageManager} contract.
 */
public class StorageInternalException extends RafenDBInternalException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public StorageInternalException(String message) {
        super(Component.SM, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause, for example an {@link java.io.IOException}
     */
    public StorageInternalException(String message, Throwable cause) {
        super(Component.SM, message, cause);
    }
}
