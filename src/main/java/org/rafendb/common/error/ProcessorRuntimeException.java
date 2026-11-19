package org.rafendb.common.error;

import org.rafendb.common.Component;

/**
 * Query Processor's {@link RafenDBRuntimeException}: an error the client caused while its statement or transaction ran, such as a transaction that was aborted.
 */
public non-sealed class ProcessorRuntimeException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message
     */
    public ProcessorRuntimeException(String message) {
        super(Component.QP, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public ProcessorRuntimeException(String message, Throwable cause) {
        super(Component.QP, message, cause);
    }
}
