package org.rafendb.optimizer.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.rafendb.common.token.SourcePosition;
import org.rafendb.common.token.Token;
import org.rafendb.common.token.TokenType;

/**
 * Minimal stand-in for the real Tokenizer, so parser tests do not depend on QO-01. It follows the
 * same token types and ends the list with EOF. Swap it for the real Tokenizer once that exists.
 */
final class TestTokens {
    private static final Set<String> KEYWORDS =
            Set.of(
                    "AND",
                    "UPDATE",
                    "SET",
                    "WHERE",
                    "INSERT",
                    "INTO",
                    "VALUES",
                    "DELETE",
                    "FROM",
                    "BEGIN",
                    "TRANSACTION",
                    "COMMIT",
                    "ABORT",
                    "CREATE",
                    "INDEX",
                    "ON",
                    "USING");
    private static final Pattern TOKEN =
            Pattern.compile(
                    "\\s*(\\d+\\.\\d+|\\d+|'[^']*'|[A-Za-z_]\\w*|<=|>=|<>|!=|[=<>+\\-*/%]|[(),.;])");

    private TestTokens() {}

    /** Splits {@code sql} into tokens followed by EOF. Input must be fully tokenizable. */
    static List<Token> lex(String sql) {
        List<Token> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(sql);
        int end = 0;
        while (m.find() && m.start() == end) {
            String text = m.group(1);
            end = m.end();
            TokenType type;
            if (text.matches("\\d+")) {
                type = TokenType.NUMBER_INT;
            } else if (text.matches("\\d+\\.\\d+")) {
                type = TokenType.NUMBER_FLOAT;
            } else if (text.startsWith("'")) {
                type = TokenType.STRING_LITERAL;
                text = text.substring(1, text.length() - 1);
            } else if (text.matches("[A-Za-z_]\\w*")) {
                type =
                        KEYWORDS.contains(text.toUpperCase())
                                ? TokenType.KEYWORD
                                : TokenType.IDENTIFIER;
            } else if (text.matches("<=|>=|<>|!=|[=<>]")) {
                type = TokenType.OPERATOR_COMPARISON;
            } else if (text.matches("[+\\-*/%]")) {
                type = TokenType.OPERATOR_ARITHMETIC;
            } else {
                type = TokenType.PUNCTUATION;
            }
            out.add(token(type, text, m.start(1)));
        }
        out.add(token(TokenType.EOF, "", sql.length()));
        return out;
    }

    private static Token token(TokenType type, String text, int offset) {
        return new Token(type, text, new SourcePosition(offset, 1, offset + 1));
    }
}
