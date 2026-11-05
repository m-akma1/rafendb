package org.rafendb.common.error;

/**
 * Query Optimizer's {@link RafenDBRuntimeException} for a statement that is valid SQL but does not
 * fit the catalog (QO-07): an unknown table or column, an ambiguous column, incompatible types, or
 * INSERT/UPDATE values that do not match the target columns. The message names the offending table
 * or column. Shown to the user.
 */
public class ValidationException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message, naming the offending table or column
     */
    public ValidationException(String message) {
        super(Component.QO, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message, naming the offending table or column
     * @param cause the underlying cause
     */
    public ValidationException(String message, Throwable cause) {
        super(Component.QO, message, cause);
    }
}
