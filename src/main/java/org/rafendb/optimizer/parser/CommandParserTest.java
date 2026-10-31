package org.rafendb.optimizer.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.query.CreateIndexQuery;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.TransactionQuery;
import org.rafendb.common.query.TransactionQuery.Kind;

class CommandParserTest {
    private static ParsedQuery parse(String sql) {
        return CommandParser.parse(sql, TestTokens.lex(sql));
    }

    private static void assertSyntaxError(String sql, String messagePart) {
        SyntaxException e = assertThrows(SyntaxException.class, () -> parse(sql));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertTrue(e.getPosition() != null, "error must carry a position");
    }

    @Test
    void beginWithAndWithoutTransaction() {
        assertEquals(new TransactionQuery("BEGIN", Kind.BEGIN), parse("BEGIN"));
        assertEquals(
                new TransactionQuery("begin transaction", Kind.BEGIN), parse("begin transaction"));
    }

    @Test
    void commitAndAbort() {
        assertEquals(new TransactionQuery("COMMIT", Kind.COMMIT), parse("COMMIT"));
        assertEquals(new TransactionQuery("ABORT;", Kind.ABORT), parse("ABORT;"));
    }

    @Test
    void createIndexKeepsNameTableAndColumn() {
        String sql = "CREATE INDEX idx_salary ON emp(salary)";
        assertEquals(new CreateIndexQuery(sql, "idx_salary", "emp", "salary"), parse(sql));
    }

    @Test
    void createIndexWithoutOnIsRejected() {
        assertSyntaxError("CREATE INDEX idx emp(salary)", "ON");
    }

    @Test
    void createIndexWithoutColumnIsRejected() {
        assertSyntaxError("CREATE INDEX idx ON emp()", "column name");
    }

    @Test
    void createSomethingElseIsRejected() {
        assertSyntaxError("CREATE emp", "INDEX");
    }

    @Test
    void trailingTokensAreRejected() {
        assertSyntaxError("COMMIT now", "end of query");
    }

    @Test
    void nonCommandStatementIsRejected() {
        assertSyntaxError("DELETE FROM emp", "BEGIN, COMMIT, ABORT, or CREATE");
    }
}
