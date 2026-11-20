package org.rafendb.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.rafendb.client.StatementSplitter.Pending;

class StatementSplitterTest {

    static Stream<Arguments> inputs() {
        return Stream.of(
                Arguments.of("SELECT 1;", List.of("SELECT 1"), Pending.NONE),
                Arguments.of("SELECT 1; SELECT 2;", List.of("SELECT 1", "SELECT 2"), Pending.NONE),
                Arguments.of("SELECT\n  *\nFROM t;", List.of("SELECT\n  *\nFROM t"), Pending.NONE),
                Arguments.of("SELECT 1", List.of(), Pending.STATEMENT),
                Arguments.of("SELECT 1; SELECT", List.of("SELECT 1"), Pending.STATEMENT),
                Arguments.of("VALUES ('a;b');", List.of("VALUES ('a;b')"), Pending.NONE),
                Arguments.of("VALUES ('it''s');", List.of("VALUES ('it''s')"), Pending.NONE),
                Arguments.of("VALUES ('--x');", List.of("VALUES ('--x')"), Pending.NONE),
                Arguments.of("VALUES ('open; still", List.of(), Pending.LITERAL),
                Arguments.of("SELECT 1; -- a; b\n", List.of("SELECT 1"), Pending.NONE),
                Arguments.of("SELECT 1 -- note\n", List.of(), Pending.STATEMENT),
                Arguments.of("-- only a comment\n", List.of(), Pending.NONE),
                Arguments.of(";; ;", List.of(), Pending.NONE),
                Arguments.of("SELECT /* a; b */ 1;", List.of("SELECT   1"), Pending.NONE),
                Arguments.of("SELECT /* a /* b; */ c; */ 1;", List.of("SELECT   1"), Pending.NONE),
                Arguments.of("SELECT 1; /* open; still", List.of("SELECT 1"), Pending.COMMENT),
                Arguments.of("VALUES ('/*');", List.of("VALUES ('/*')"), Pending.NONE),
                Arguments.of("SELECT 1 -- /* not a block\n;", List.of("SELECT 1"), Pending.NONE));
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void splitsIntoStatementsAndPending(String text, List<String> statements, Pending pending) {
        StatementSplitter.Split split = StatementSplitter.split(text);
        assertEquals(statements, split.statements());
        assertEquals(pending, split.pending());
    }

    @ParameterizedTest
    @MethodSource("inputs")
    void restContinuesToTheSameStatements(String text, List<String> statements, Pending pending) {
        // Typing more onto the rest must give what splitting the whole text at once gives.
        StatementSplitter.Split first = StatementSplitter.split(text);
        String more =
                switch (pending) {
                    case LITERAL -> "';";
                    case COMMENT -> "*/;";
                    default -> ";";
                };
        StatementSplitter.Split whole = StatementSplitter.split(text + more);
        StatementSplitter.Split continued = StatementSplitter.split(first.rest() + more);
        List<String> joined =
                Stream.concat(first.statements().stream(), continued.statements().stream())
                        .toList();
        assertEquals(whole.statements(), joined);
    }
}
