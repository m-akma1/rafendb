package org.rafendb.common.error;

import org.rafendb.common.token.SourcePosition;

public class ErrorMessage {
    public ErrorCode code;
    public String message;
    public SourcePosition position;

    public ErrorMessage(ErrorCode code, String message, SourcePosition position) {
        this.code = code;
        this.message = message;
        this.position = position;
    }

    public ErrorCode getCode() {
        return code;
    }

    public String getMsg() {
        return message;
    }

    public SourcePosition getPosition() {
        return position;
    }

    @Override
    public String toString() {
        return code + ": " + message + (position != null ? " (" + position + ")" : "");
    }
}
