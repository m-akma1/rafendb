package org.rafendb.common.error;

/** Semantic validation failure raised while binding a query. */
public class ValidationException extends QoException {
    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
    }

    public ValidationException(String message, Throwable cause) {
        super(ErrorCode.VALIDATION_ERROR, message, null, cause);
    }
}
