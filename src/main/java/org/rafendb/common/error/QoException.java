package org.rafendb.common.error;

import org.rafendb.common.token.SourcePosition;

public class QoException extends RuntimeException {

    private final ErrorCode code;
    private final SourcePosition position;

    public QoException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public QoException(ErrorCode code, String message, SourcePosition position) {
        this(code, message, position, null);
    }

    public QoException(ErrorCode code, String message, SourcePosition position, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.position = position;
    }

    public ErrorCode getCode() {
        return code;
    }

    public SourcePosition getPosition() {
        return position;
    }

    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(code, getMessage(), position);
    }
}
