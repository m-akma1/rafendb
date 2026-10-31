package org.rafendb.optimizer.parser;

import java.util.List;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.token.Token;
import org.rafendb.common.token.TokenType;

/**
 * Read position over the token list from the Tokenizer, shared by the parsers in this package.
 *
 * <p>Not thread-safe: use one instance per query. The list must be non-empty and end with an EOF
 * token; the cursor never moves past EOF.
 */
final class TokenCursor {
    private final List<Token> tokens;
    private int pos;

    /**
     * Creates a cursor at the first token.
     *
     * @param tokens the Tokenizer output, non-empty and ending with EOF
     */
    TokenCursor(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Returns the current token without consuming it. */
    Token peek() {
        return tokens.get(pos);
    }

    /** Consumes and returns the current token. EOF is returned repeatedly, never skipped. */
    Token next() {
        Token t = tokens.get(pos);
        if (!t.isEof()) {
            pos++;
        }
        return t;
    }

    /** Consumes the current token if it is the given keyword; returns whether it did. */
    boolean acceptKeyword(String keyword) {
        if (!peek().isKeyword(keyword)) {
            return false;
        }
        pos++;
        return true;
    }

    /** Consumes the current token if it is the given punctuation; returns whether it did. */
    boolean acceptPunct(String symbol) {
        if (!peek().isPunctuation(symbol)) {
            return false;
        }
        pos++;
        return true;
    }

    /** Consumes the given keyword or throws {@link SyntaxException}. */
    void expectKeyword(String keyword) {
        if (!acceptKeyword(keyword)) {
            throw error(peek(), keyword);
        }
    }

    /** Consumes the given punctuation or throws {@link SyntaxException}. */
    void expectPunct(String symbol) {
        if (!acceptPunct(symbol)) {
            throw error(peek(), "'" + symbol + "'");
        }
    }

    /** Consumes the given comparison operator (e.g. "=") or throws {@link SyntaxException}. */
    void expectComparison(String symbol) {
        Token t = peek();
        if (t.getType() != TokenType.OPERATOR_COMPARISON || !t.getText().equals(symbol)) {
            throw error(t, "'" + symbol + "'");
        }
        pos++;
    }

    /** Consumes an identifier and returns its text, or throws {@link SyntaxException}. */
    String expectIdentifier(String what) {
        Token t = peek();
        if (t.getType() != TokenType.IDENTIFIER) {
            throw error(t, what);
        }
        pos++;
        return t.getText();
    }

    /** Accepts an optional trailing ';' then requires end of input, else throws. */
    void expectEnd() {
        acceptPunct(";");
        if (!peek().isEof()) {
            throw error(peek(), "end of query");
        }
    }

    /** Builds a syntax error naming the expectation and the offending token (QO-01). */
    SyntaxException error(Token found, String expected) {
        String what = found.isEof() ? "end of input" : "'" + found.getText() + "'";
        return new SyntaxException("Expected " + expected + " but found " + what, found);
    }
}
