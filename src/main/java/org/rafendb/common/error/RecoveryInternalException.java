package org.rafendb.common.error;

/**
 * Failure Recovery Manager's {@link RafenDBInternalException}: a failure while writing or forcing the
 * log, or while rolling back or recovering.
 */
public class RecoveryInternalException extends RafenDBInternalException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public RecoveryInternalException(String message) {
        super(Component.FRM, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause, for example an I/O error
     */
    public RecoveryInternalException(String message, Throwable cause) {
        super(Component.FRM, message, cause);
    }
}
