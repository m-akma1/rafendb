package org.rafendb.common.error;

import org.rafendb.common.Component;

/**
 * {@link Component#GENERAL}'s {@link RafenDBRuntimeException}: a client error that belongs to no single component, such as bad input to a driver.
 */
public non-sealed class GeneralRuntimeException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public GeneralRuntimeException(String message) {
        super(Component.GENERAL, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public GeneralRuntimeException(String message, Throwable cause) {
        super(Component.GENERAL, message, cause);
    }
}
