package org.rafendb.common.error;

public class RafenDBInternalException extends Exception {
    private final Component component;

    protected RafenDBInternalException(Component component, String msg, Throwable cause) {
        super(msg, cause);
        this.component = component;
    }

    @Override
    public String getMessage() {
        return "Internal error at " + component.displayName() + ": " + super.getMessage();
    }
}
