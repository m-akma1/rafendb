package org.rafendb.common.token;

// why is it in common? bcs SyntaxException later uses this (also in common)
public class Token {
    private TokenType type;
    private String text;
    private SourcePosition position;

    public Token(TokenType type, String text, SourcePosition position) {
        this.type = type;
        this.text = text;
        this.position = position;
    }

    public TokenType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public SourcePosition getPosition() {
        return position;
    }

    public boolean isKeyword(String keyword) {
        return type == TokenType.KEYWORD && text.equalsIgnoreCase(keyword);
    }

    public boolean isPunctuation(String symbol) {
        return type == TokenType.PUNCTUATION && text.equals(symbol);
    }

    public boolean isEof() {
        return type == TokenType.EOF;
    }
}
