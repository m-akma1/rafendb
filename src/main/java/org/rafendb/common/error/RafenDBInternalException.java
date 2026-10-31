package org.rafendb.common.error;

/**
 * Root checked exception. An error the client did not cause, such as a disk failure or a broken
 * contract between components.
 *
 * <p>
 * DO NOT SHOW TO CLIENT, INTERNAL MUST BE HANDLED BY INTERNAL CALLER.
 */
public class RafenDBInternalException extends Exception {
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
}
