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
import org.rafendb.common.schema.IndexType;

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
    void createIndexDefaultsToBtree() {
        String sql = "CREATE INDEX idx_salary ON emp(salary)";
        assertEquals(
                new CreateIndexQuery(sql, "idx_salary", "emp", "salary", IndexType.BTREE),
                parse(sql));
    }

    @Test
    void createIndexUsingHashOrBtree() {
        String hash = "CREATE INDEX idx ON emp(id) USING HASH";
        assertEquals(new CreateIndexQuery(hash, "idx", "emp", "id", IndexType.HASH), parse(hash));
        String btree = "create index idx on emp(id) using btree;";
        assertEquals(new CreateIndexQuery(btree, "idx", "emp", "id", IndexType.BTREE), parse(btree));
    }

    @Test
    void createIndexWithUnknownTypeIsRejected() {
        assertSyntaxError("CREATE INDEX idx ON emp(id) USING GIST", "BTREE or HASH");
        assertSyntaxError("CREATE INDEX idx ON emp(id) USING", "BTREE or HASH");
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