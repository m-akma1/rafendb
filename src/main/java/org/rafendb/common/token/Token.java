package org.rafendb.common.token;

import java.util.Objects;
import java.util.Set;

// why is it in common? bcs SyntaxException later uses this (also in common)
public record Token(TokenType type, String text, SourcePosition position) {
    /** Unique identifier for serialization. */
    private static final long serialVersionUID = 1L;

    /**
     * Lexical category of a {@link Token}.
     *
     * <p>Nested in {@link Token} because it has no meaning outside a token and is
     * only consumed together with it.</p>
     */
    public enum TokenType {
        KEYWORD,
        IDENTIFIER,
        INTEGER_LITERAL,
        FLOAT_LITERAL,
        STRING_LITERAL,
        COMPARISON_OPERATOR,
        ARITHMETIC_OPERATOR,
        PUNCTUATION,
        UNKNOWN,
        EOF;

        /** Returns {@code true} for numeric and string literals. */
        public boolean isLiteral() {
            return this == INTEGER_LITERAL || this == FLOAT_LITERAL || this == STRING_LITERAL;
        }

        /** Returns {@code true} for comparison and arithmetic operators. */
        public boolean isOperator() {
            return this == COMPARISON_OPERATOR || this == ARITHMETIC_OPERATOR;
        }
    }

    private static final Set<String> KEYWORDS = Set.of(
        "SELECT", "FROM", "WHERE", "AS", "JOIN", "NATURAL", "ON", "AND", "ORDER", "BY", "ASC", "DESC",
        "UPDATE", "SET", "INSERT", "INTO", "VALUES", "DELETE",
        "BEGIN", "TRANSACTION", "COMMIT", "ABORT",
        "CREATE", "INDEX", "USING", "HASH", "BTREE");

    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(position, "position");
    }

    public static boolean isTextKeyword(String keyword) {
        return KEYWORDS.contains(keyword.toUpperCase());
    }

    public boolean isKeyword(String keyword) {
        return type == TokenType.KEYWORD && KEYWORDS.contains(keyword.toUpperCase());
    }

    public boolean isPunctuation(String symbol) {
        return type == TokenType.PUNCTUATION && text.equals(symbol);
    }

    public boolean isEof() {
        return type == TokenType.EOF;
    }
}
