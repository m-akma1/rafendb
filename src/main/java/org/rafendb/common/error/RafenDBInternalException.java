package org.rafendb.common.error;

import org.rafendb.common.Component;

/**
 * Root checked exception. An error the client did not cause, such as a disk failure or a broken
 * contract between components.
 *
 * <p>
 * DO NOT SHOW TO CLIENT, INTERNAL MUST BE HANDLED BY INTERNAL CALLER.
 */
public abstract sealed class RafenDBInternalException extends Exception
        permits StorageInternalException,
                RecoveryInternalException,
                ConcurrencyInternalException,
                OptimizerInternalException {
    private static final long serialVersionUID = 1L;
    private final Component component;

    /**
     * Creates an internal exception for a component.
     *
     * @param component component where the error occurred. If{@code null} uses
     *                  {@link Component#GENERAL}
     * @param msg       error description
     * @param cause     underlying cause
     */
    protected RafenDBInternalException(Component component, String msg, Throwable cause) {
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
        return "Internal error at " + component.displayName() + ": " + super.getMessage();
    }

    /**
     * Returns the component this error is attributed to.
     *
     * @return the component, never {@code null}
     */
    public Component component() {
        return component;
    }
}
