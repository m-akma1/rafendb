package org.rafendb.common.error;

public class RafenDBRuntimeException extends RuntimeException {
    private final Component component;

    protected RafenDBRuntimeException(Component component, String msg, Throwable cause) {
        super(msg, cause);
        this.component = component;
    }

    @Override
    public String getMessage() {
        return "Runtime error at " + component.displayName() + ": " + super.getMessage();
    }
}
