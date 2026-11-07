package org.rafendb.optimizer.lexer;

import java.util.List;
import java.util.Stack;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.token.SourcePosition;
import org.rafendb.common.token.Token;
import org.rafendb.common.token.Token.TokenType;

public class TokenizerImpl implements Tokenizer {
    private String sql;
    private int offset;
    private int line;
    private int column;

    public TokenizerImpl() {
        this.sql = null;
        this.offset = 0;
        this.line = 1;
        this.column = 1;
    }

    @Override
    public List<Token> tokenize(String sql) throws SyntaxException {
        if (sql == null || sql.isBlank()) {
            throw new SyntaxException("Empty Query");
        }
        this.sql = sql;
        this.offset = 0;
        this.line = 1;
        this.column = 1;
        List<Token> tokens = new java.util.ArrayList<>();
        while (offset < sql.length()) {
            skipWhitespace();
            if (offset >= sql.length()) {
                break;
            }
            if (sql.charAt(offset) == '\'' || sql.charAt(offset) == '"') {
                tokens.add(readStringLiteral());
            } else if (Character.isLetter(sql.charAt(offset)) || sql.charAt(offset) == '_') {
                tokens.add(readWordOrIdentifier());
            } else if (Character.isDigit(sql.charAt(offset))) {
                tokens.add(readNumber());
            } else if ("=<>!+-*/".indexOf(sql.charAt(offset)) != -1) {
                tokens.add(readOperator());
            } else if (",();".indexOf(sql.charAt(offset)) != -1) {
                tokens.add(readPunctuation());
            } else {
                throw error(
                        "Illegal character: " + sql.charAt(offset),
                        new Token(Token.TokenType.UNKNOWN, String.valueOf(sql.charAt(offset)), currentPosition()));
            }
        }
        checkBalancedParentheses(tokens);
        tokens.add(new Token(Token.TokenType.EOF, "", currentPosition()));
        return tokens;
    }

    private void skipWhitespace() {
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (c == ' ' || c == '\t' || c == '\r') {
                offset++;
                column++;
            } else if (c == '\n') {
                offset++;
                line++;
                column = 1;
            } else {
                break;
            }
        }
    }

    private Token readWordOrIdentifier() throws SyntaxException {
        int start = offset;
        boolean seenDot = false;
        SourcePosition startPosition = currentPosition();
        while (offset < sql.length()){
            char c = sql.charAt(offset);
            if (Character.isLetterOrDigit(c) || c == '_') {
                offset++; column++;
            } else if (c == '.') {
                char next = offset + 1 < sql.length() ? sql.charAt(offset + 1) : '\0';
                boolean validNext = Character.isLetter(next) || next == '_' || next == '*';
                if (seenDot || !validNext) {
                    throw error("Malformed identifier",
                            new Token(TokenType.IDENTIFIER, sql.substring(start, offset + 1), startPosition));
                }
                seenDot = true;
                offset++; column++;
                if (next == '*') { offset++; column++; break; }
            } else {
                break;
            }
        }
        String word = sql.substring(start, offset);
        if (!seenDot && Token.isTextKeyword(word)) { 
            return new Token(TokenType.KEYWORD, word.toUpperCase(), startPosition);
        }
        return new Token(TokenType.IDENTIFIER, word, startPosition);
    }

    private Token readNumber() throws SyntaxException {
        int start = offset;
        SourcePosition startPosition = currentPosition();
        boolean hasDot = false;
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (Character.isDigit(c)) {
                offset++;
                column++;
            } else if (c == '.' && !hasDot) {
                hasDot = true;
                offset++;
                column++;
            } else {
                break;
            }
        }
        String number = sql.substring(start, offset);
        if (number.isEmpty()) {
            throw error("Malformed number literal", new Token(Token.TokenType.INTEGER_LITERAL, "", startPosition));
        }
        if (offset < sql.length() && Character.isLetter(sql.charAt(offset))) {
            throw error(
                    "Malformed number literal: " + sql.substring(start, offset + 1),
                    new Token(Token.TokenType.INTEGER_LITERAL, sql.substring(start, offset + 1), startPosition));
        }
        if (hasDot) {
            if (number.startsWith(".") || number.endsWith(".")) {
                throw error("Malformed float literal: " + number, new Token(Token.TokenType.FLOAT_LITERAL, number, startPosition));
            } else if (number.chars().filter(ch -> ch == '.').count() > 1) {
                throw error("Malformed float literal: " + number, new Token(Token.TokenType.FLOAT_LITERAL, number, startPosition));
            } else {
                return new Token(Token.TokenType.FLOAT_LITERAL, number, startPosition);
            }
        }
        return new Token(Token.TokenType.INTEGER_LITERAL, number, startPosition);
    }

    private Token readStringLiteral() throws SyntaxException {
        char quoteChar = sql.charAt(offset);
        int start = offset;
        SourcePosition startPosition = currentPosition();
        offset++;
        column++;
        StringBuilder sb = new StringBuilder();
        while (offset < sql.length()) {
            char c = sql.charAt(offset);
            if (c == quoteChar) {
                offset++;
                column++;
                if (offset < sql.length() && sql.charAt(offset) == quoteChar) {
                    sb.append(quoteChar);
                    offset++;
                    column++;
                } else {
                    return new Token(Token.TokenType.STRING_LITERAL, sb.toString(), startPosition);
                }
            } else {
                sb.append(c);
                offset++;
                column++;
            }
        }
        throw error("Unterminated string literal", new Token(Token.TokenType.STRING_LITERAL, sql.substring(start, offset), startPosition));
    }

    private Token readOperator() throws SyntaxException {
        char c = sql.charAt(offset);
        SourcePosition startPosition = currentPosition();
        if (c == '=') {
            offset++;
            column++;
            return new Token(Token.TokenType.COMPARISON_OPERATOR, "=", startPosition);
        } else if (c == '<') {
            offset++;
            column++;
            if (offset < sql.length() && sql.charAt(offset) == '>') {
                offset++;
                column++;
                return new Token(Token.TokenType.COMPARISON_OPERATOR, "<>", startPosition);
            } else if (offset < sql.length() && sql.charAt(offset) == '=') {
                offset++;
                column++;
                return new Token(Token.TokenType.COMPARISON_OPERATOR, "<=", startPosition);
            } else {
                return new Token(Token.TokenType.COMPARISON_OPERATOR, "<", startPosition);
            }
        } else if (c == '>') {
            offset++;
            column++;
            if (offset < sql.length() && sql.charAt(offset) == '=') {
                offset++;
                column++;
                return new Token(Token.TokenType.COMPARISON_OPERATOR, ">=", startPosition);
            } else {
                return new Token(Token.TokenType.COMPARISON_OPERATOR, ">", startPosition);
            }
        } else if (c == '!') {
            offset++;
            column++;
            if (offset < sql.length() && sql.charAt(offset) == '=') {
                offset++;
                column++;
                return new Token(Token.TokenType.COMPARISON_OPERATOR, "!=", startPosition);
            } else {
                throw error("Illegal operator: !", new Token(Token.TokenType.COMPARISON_OPERATOR, "!", startPosition));
            }
        } else if ("+-*/".indexOf(c) != -1) {
            offset++;
            column++;
            return new Token(
                    Token.TokenType.ARITHMETIC_OPERATOR, String.valueOf(c), startPosition);
        } else {
            throw error("Illegal operator: " + c, new Token(Token.TokenType.ARITHMETIC_OPERATOR, String.valueOf(c), startPosition));
        }
    }

    private Token readPunctuation() throws SyntaxException {
        char c = sql.charAt(offset);
        SourcePosition startPosition = currentPosition();
        if (",();".indexOf(c) != -1) {
            offset++;
            column++;
            return new Token(Token.TokenType.PUNCTUATION, String.valueOf(c), startPosition);
        } else {
            throw error("Illegal punctuation: " + c, new Token(Token.TokenType.PUNCTUATION, String.valueOf(c), startPosition));
        }
    }

    private void checkBalancedParentheses(List<Token> tokens) throws SyntaxException {
        Stack<Token> stack = new Stack<>();
        for (Token token : tokens) {
            if (token.type() == Token.TokenType.PUNCTUATION) {
                String text = token.text();
                if (text.equals("(")) {
                    stack.push(token);
                } else if (text.equals(")")) {
                    if (stack.isEmpty()) {
                        throw error("Unmatched closing parenthesis", token);
                    }
                    stack.pop();
                }
            }
        }
        if (stack.size() > 0) {
            throw new SyntaxException("Unmatched opening parenthesis at " + stack.peek().position(), stack.peek());
        }
    }

    private SourcePosition currentPosition() {
        return new SourcePosition(offset, line, column);
    }

    private SyntaxException error(String message, Token token) {
        return new SyntaxException(message + " at " + token.position(), token);
    }
}
