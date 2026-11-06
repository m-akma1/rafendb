package org.rafendb.common.error;

/**
 * Query Optimizer's {@link RafenDBRuntimeException} for a statement that is not valid SQL (QO-01):
 * a misspelled keyword, an incomplete clause, wrong clause order, unbalanced parentheses or an
 * unterminated string. The message names the offending token or its position. Shown to the user.
 */
public class SyntaxException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message the detail message, naming the offending token or position
     */
    public SyntaxException(String message) {
        super(Component.QO, message, null);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message the detail message, naming the offending token or position
     * @param cause the underlying cause
     */
    public SyntaxException(String message, Throwable cause) {
        super(Component.QO, message, cause);
    }
}
