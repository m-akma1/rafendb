package org.rafendb.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits typed text into SQL statements (QP-01). A statement ends at a {@code ;} that is outside a
 * {@code '...'} literal ({@code ''} is an escaped quote), a {@code -- ...} line comment and a
 * {@code /* ... *}{@code /} block comment, which may nest as in PostgreSQL. Comments are dropped
 * from the statements (a block comment becomes one space); the text after the last {@code ;} is
 * returned as is so the caller can keep typing onto it.
 */
final class StatementSplitter {
    private StatementSplitter() {}

    /** Why the text after the last complete statement is unfinished. */
    enum Pending {
        /** Nothing is left but blanks or comments. */
        NONE,
        /** A statement was started but has no {@code ;} yet. */
        STATEMENT,
        /** A {@code '...'} literal is still open. */
        LITERAL,
        /** A block comment is still open. */
        COMMENT
    }

    /**
     * The result of a split.
     *
     * @param statements the complete statements in order, without {@code ;} and comments, trimmed,
     *     never blank
     * @param rest the unfinished text after the last complete statement, exactly as typed; empty
     *     when {@code pending} is {@link Pending#NONE}
     * @param pending why {@code rest} is unfinished
     */
    record Split(List<String> statements, String rest, Pending pending) {}

    /**
     * Splits text into complete statements and an unfinished rest.
     *
     * @param text the text typed so far, possibly several lines
     * @return the split
     */
    static Split split(String text) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int start = 0;
        boolean literal = false;
        int comments = 0; // depth of nested block comments
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (comments > 0) {
                if (c == '/' && next == '*') {
                    comments++;
                    i++;
                } else if (c == '*' && next == '/') {
                    comments--;
                    i++;
                    if (comments == 0) {
                        current.append(' ');
                    }
                }
            } else if (literal) {
                current.append(c);
                if (c == '\'' && next == '\'') {
                    current.append(next);
                    i++;
                } else if (c == '\'') {
                    literal = false;
                }
            } else if (c == '\'') {
                literal = true;
                current.append(c);
            } else if (c == '/' && next == '*') {
                comments = 1;
                i++;
            } else if (c == '-' && next == '-') {
                while (i + 1 < text.length() && text.charAt(i + 1) != '\n') {
                    i++;
                }
            } else if (c == ';') {
                String statement = current.toString().strip();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
                start = i + 1;
            } else {
                current.append(c);
            }
        }
        Pending pending;
        if (comments > 0) {
            pending = Pending.COMMENT;
        } else if (literal) {
            pending = Pending.LITERAL;
        } else {
            pending = current.toString().isBlank() ? Pending.NONE : Pending.STATEMENT;
        }
        String rest = pending == Pending.NONE ? "" : text.substring(start);
        return new Split(List.copyOf(statements), rest, pending);
    }
}
