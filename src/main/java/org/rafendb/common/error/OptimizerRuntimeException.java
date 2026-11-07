package org.rafendb.common.error;

import org.rafendb.common.token.Token;

/**
 * Query Optimizer's {@link RafenDBRuntimeException}: a statement the client got wrong. Shown to the
 * user. Catch this type to handle every client error from QO; the nested classes tell the kinds
 * apart:
 *
 * <ul>
 *   <li>{@link SyntaxException} (QO-01)
 *   <li>{@link ValidationException} (QO-07)
 * </ul>
 */
public abstract class OptimizerRuntimeException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode code;

    /**
     * Creates an exception.
     *
     * @param code the error code for this kind of error
     * @param message the detail message
     * @param cause the underlying cause; {@code null} if none
     */
    protected OptimizerRuntimeException(ErrorCode code, String message, Throwable cause) {
        super(Component.QO, message, cause);
        this.code = code;
    }

    /**
     * Returns the error code reported to the Query Processor.
     *
     * @return {@link ErrorCode#SYNTAX_ERROR} or {@link ErrorCode#VALIDATION_ERROR}
     */
    public ErrorCode getCode() {
        return code;
    }

    /**
     * A statement that is not valid SQL (QO-01): a misspelled keyword, an incomplete clause, wrong
     * clause order, unbalanced parentheses or an unterminated string. The message names the
     * offending token or its position.
     */
    public static class SyntaxException extends OptimizerRuntimeException {
        private static final long serialVersionUID = 1L;

        private final Token offendingToken;

        /**
         * Creates an exception with a message.
         *
         * @param message the detail message, naming the offending token or position
         */
        public SyntaxException(String message) {
            super(ErrorCode.SYNTAX_ERROR, message, null);
            this.offendingToken = null;
        }

        /**
         * Creates an exception caused by a token.
         *
         * @param message the detail message, naming the offending token or position
         * @param offendingToken the token that caused the error
         */
        public SyntaxException(String message, Token offendingToken) {
            super(ErrorCode.SYNTAX_ERROR, message, null);
            this.offendingToken = offendingToken;
        }

        /**
         * Creates an exception with a message and a cause.
         *
         * @param message the detail message, naming the offending token or position
         * @param cause the underlying cause
         */
        public SyntaxException(String message, Throwable cause) {
            super(ErrorCode.SYNTAX_ERROR, message, cause);
            this.offendingToken = null;
        }

        /**
         * Returns the token that caused the error.
         *
         * @return the token, or {@code null} if none was recorded
         */
        public Token getOffendingToken() {
            return offendingToken;
        }
    }

    /**
     * A statement that is valid SQL but does not fit the catalog (QO-07): an unknown table or column,
     * an ambiguous column, incompatible types, or INSERT/UPDATE values that do not match the target
     * columns. The message names the offending table or column.
     */
    public static class ValidationException extends OptimizerRuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception with a message.
         *
         * @param message the detail message, naming the offending table or column
         */
        public ValidationException(String message) {
            super(ErrorCode.VALIDATION_ERROR, message, null);
        }

        /**
         * Creates an exception with a message and a cause.
         *
         * @param message the detail message, naming the offending table or column
         * @param cause the underlying cause
         */
        public ValidationException(String message, Throwable cause) {
            super(ErrorCode.VALIDATION_ERROR, message, cause);
        }
    }
}
