package org.rafendb.common.error;

/**
 * Concurrency Control Manager's {@link RafenDBInternalException}: a failure while logging or rolling
 * back through the Failure Recovery Manager.
 */
public class ConcurrencyInternalException extends RafenDBInternalException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public ConcurrencyInternalException(String message) {
        super(Component.CCM, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause, for example the FRM error
     */
    public ConcurrencyInternalException(String message, Throwable cause) {
        super(Component.CCM, message, cause);
    }
}
