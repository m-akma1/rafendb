package org.rafendb.server;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

class ResultRendererTest {

    @Test
    void rendersTableWithHeaderNullsAndRowCount() {
        QueryResult result =
                new QueryResult.Rows(
                        List.of("id", "name"),
                        List.of(
                                new Row(
                                        List.of(
                                                Value.ofInt(1),
                                                Value.ofText(DataType.VARCHAR, "Alice"))),
                                new Row(List.of(Value.ofInt(20), Value.nullOf(DataType.VARCHAR)))));

        String expected =
                """
                +----+-------+
                | id | name  |
                +----+-------+
                | 1  | Alice |
                | 20 | NULL  |
                +----+-------+
                (2 rows)""";
        assertEquals(expected, ResultRenderer.render(result));
    }

    @Test
    void emptyResultKeepsHeader() {
        QueryResult result = new QueryResult.Rows(List.of("id"), List.of());

        String expected =
                """
                +----+
                | id |
                +----+
                (0 rows)""";
        assertEquals(expected, ResultRenderer.render(result));
    }

    @Test
    void rendersAffectedCount() {
        assertEquals("1 row affected", ResultRenderer.render(new QueryResult.Affected(1)));
        assertEquals("3 rows affected", ResultRenderer.render(new QueryResult.Affected(3)));
    }

    @Test
    void rendersStatusMessage() {
        assertEquals(
                "Transaction started",
                ResultRenderer.render(new QueryResult.Status("Transaction started")));
    }
}
