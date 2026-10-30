package org.rafendb.common.error;

import org.rafendb.common.token.SourcePosition;
import org.rafendb.common.token.Token;

/** Kesalahan sintaks (QO-01). */
public class SyntaxException extends QoException {

    private final Token offendingToken;

    public SyntaxException(String message, Token offendingToken) {
        super(ErrorCode.SYNTAX_ERROR, message, offendingToken == null ? null : offendingToken.getPosition());
        this.offendingToken = offendingToken;
    }

    public SyntaxException(String message, SourcePosition position) {
        super(ErrorCode.SYNTAX_ERROR, message, position);
        this.offendingToken = null;
    }

    public Token getOffendingToken() {
        return offendingToken;
    }
}