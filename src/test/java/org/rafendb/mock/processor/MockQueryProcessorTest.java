package org.rafendb.mock.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.api.processor.Session;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.query.QueryResult;

class MockQueryProcessorTest {

    private Session session;

    @BeforeEach
    void setUp() {
        session = new MockQueryProcessor().openSession();
    }

    @Test
    void selectReturnsSampleRows() {
        assertEquals(MockQueryProcessor.SAMPLE_ROWS, session.execute("select * from t;"));
    }

    @Test
    void dataChangesReturnOneAffectedRow() {
        for (String sql :
                new String[] {"INSERT INTO t VALUES (1)", "update t set a = 1", "DELETE FROM t;"}) {
            assertEquals(new QueryResult.Affected(1), session.execute(sql), sql);
        }
    }

    @Test
    void transactionAndIndexCommandsReturnStatus() {
        for (String sql : new String[] {"BEGIN;", "commit", "ABORT", "CREATE INDEX i ON t(a)"}) {
            assertInstanceOf(QueryResult.Status.class, session.execute(sql), sql);
        }
    }

    @Test
    void unknownOrEmptyStatementThrowsSyntaxException() {
        assertThrows(SyntaxException.class, () -> session.execute("SELEC * FROM t"));
        assertThrows(SyntaxException.class, () -> session.execute("  ;"));
    }

    @Test
    void sessionStaysUsableAfterError() {
        assertThrows(SyntaxException.class, () -> session.execute("foo"));
        assertEquals(new QueryResult.Affected(1), session.execute("DELETE FROM t"));
    }

    @Test
    void executeAfterCloseThrowsAndCloseIsIdempotent() {
        session.close();
        session.close();
        assertThrows(IllegalStateException.class, () -> session.execute("SELECT * FROM t"));
    }
}
