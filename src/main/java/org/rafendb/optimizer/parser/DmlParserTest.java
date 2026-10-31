package org.rafendb.optimizer.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.Binary;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.expr.Expr.Literal;
import org.rafendb.common.expr.Expr.Op;
import org.rafendb.common.query.DeleteQuery;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

class DmlParserTest {
    private static ParsedQuery parse(String sql) {
        return DmlParser.parse(sql, TestTokens.lex(sql));
    }

    private static Literal i(int v) {
        return new Literal(Value.ofInt(v));
    }

    private static ColumnRef c(String column) {
        return new ColumnRef(null, column);
    }

    private static void assertSyntaxError(String sql, String messagePart) {
        SyntaxException e = assertThrows(SyntaxException.class, () -> parse(sql));
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
        assertTrue(e.getPosition() != null, "error must carry a position");
    }

    @Test
    void updateWithWhere() {
        String sql = "UPDATE emp SET salary = 2 * salary WHERE id = 3";
        UpdateQuery expected =
                new UpdateQuery(
                        sql,
                        "emp",
                        "salary",
                        new Binary(Op.MUL, i(2), c("salary")),
                        new Binary(Op.EQ, c("id"), i(3)));
        assertEquals(expected, parse(sql));
    }

    @Test
    void updateWithoutWhereAndWithSemicolon() {
        UpdateQuery q = (UpdateQuery) parse("UPDATE emp SET age = 30;");
        assertNull(q.where());
        assertEquals("age", q.column());
    }

    @Test
    void keywordsAreCaseInsensitive() {
        assertEquals("emp", ((DeleteQuery) parse("delete from emp")).table());
    }

    @Test
    void insertSingleRow() {
        String sql = "INSERT INTO emp VALUES (1, 'bob', 2.5)";
        List<Expr> values =
                List.of(
                        i(1),
                        new Literal(Value.ofText(DataType.VARCHAR, "bob")),
                        new Literal(Value.ofFloat(2.5f)));
        assertEquals(new InsertQuery(sql, "emp", values), parse(sql));
    }

    @Test
    void deleteWithAndWithoutWhere() {
        assertEquals(new DeleteQuery("DELETE FROM emp", "emp", null), parse("DELETE FROM emp"));
        DeleteQuery q = (DeleteQuery) parse("DELETE FROM emp WHERE a > 1 AND b < 2");
        assertEquals(Op.AND, ((Binary) q.where()).op());
    }

    @Test
    void updateWithoutSetIsRejected() {
        assertSyntaxError("UPDATE emp WHERE a = 1", "SET");
    }

    @Test
    void updateWithTwoSetColumnsIsRejected() {
        assertSyntaxError("UPDATE emp SET a = 1, b = 2", "','");
    }

    @Test
    void insertWithoutValuesIsRejected() {
        assertSyntaxError("INSERT INTO emp", "VALUES");
    }

    @Test
    void insertWithUnclosedParenthesisIsRejected() {
        assertSyntaxError("INSERT INTO emp VALUES (1, 2", "')'");
    }

    @Test
    void deleteWithoutFromIsRejected() {
        assertSyntaxError("DELETE emp", "FROM");
    }

    @Test
    void trailingTokensAreRejected() {
        assertSyntaxError("DELETE FROM emp WHERE a = b = c", "'='");
    }

    @Test
    void nonDmlStatementIsRejected() {
        assertSyntaxError("COMMIT", "UPDATE, INSERT, or DELETE");
    }
}