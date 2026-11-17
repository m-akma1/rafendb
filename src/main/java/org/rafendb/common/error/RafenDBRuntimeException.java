package org.rafendb.common.error;

import org.rafendb.common.Component;

/**
 * Root unchecked exception: an error the client caused with its request. The
 * message is shown to the user.
 */
public abstract sealed class RafenDBRuntimeException extends RuntimeException
        permits StorageRuntimeException,
                OptimizerRuntimeException,
                ProcessorRuntimeException,
                GeneralRuntimeException {
    private static final long serialVersionUID = 1L;

    /** The RafenDB component associated with this error. */
    private final Component component;

    /**
     * Creates a runtime exception for the specified component.
     *
     * @param component the component where the error occurred; {@code null}
     *                  defaults to {@link Component#GENERAL}
     * @param msg       the detail message
     * @param cause     the underlying cause
     */
    protected RafenDBRuntimeException(Component component, String msg, Throwable cause) {
        super(msg, cause);
        if (component == null) this.component = Component.GENERAL;
        else this.component = component;
    }

    /**
     * Returns the message prefixed with the component.
     *
     * @return the prefixed message
     */
    @Override
    public String getMessage() {
        return "Runtime error at " + component.displayName() + ": " + super.getMessage();
    }
}
