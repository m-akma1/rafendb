package org.rafendb.processor.subquery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.TruthValue;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.filter.FilterOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class SubqueryEvaluatorTest {
    private static final Value ONE = Value.ofInt(1);
    private static final Value TWO = Value.ofInt(2);
    private static final Value THREE = Value.ofInt(3);
    private static final Value NULL_INT = Value.nullOf(DataType.INTEGER);

    private static final List<ColumnSchema> OUTER =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef("m", "nim"), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef("m", "ipk"), DataType.FLOAT));
    private static final Expr.ColumnRef NIM = new Expr.ColumnRef("m", "nim", 0);
    private static final Expr.ColumnRef IPK = new Expr.ColumnRef("m", "ipk", 1);
    private static final ResultRow ROW = row(2, Value.ofFloat(3.5f));

    // m.ipk > 3.0
    private static final Expr IPK_ABOVE_3 =
            new Expr.Binary(Expr.Op.GT, IPK, new Expr.Literal(Value.ofFloat(3.0f)));

    private static final Expr.ColumnRef N_NIM = new Expr.ColumnRef("n", "nim", 0);

    // SELECT n.nim FROM nilai n
    private static final SelectQuery QUERY = query(null);

    // Runner palsu: selalu balikin FakeChildOp yang sama, nyatet query sama jumlah panggilan.
    private static final class FakeRunner implements SubqueryRunner {
        private final FakeChildOp result;
        private SelectQuery query;
        private int calls;

        private FakeRunner(DataType type, Value... values) {
            List<ColumnSchema> schema =
                    List.of(new ColumnSchema(new Expr.ColumnRef("n", "nim"), type));
            List<ResultRow> rows = new ArrayList<>();
            for (Value value : values) {
                rows.add(new ResultRow(List.of(value), null));
            }
            result = new FakeChildOp(schema, rows);
        }

        @Override
        public ExecutionPlan.Rows prepare(SelectQuery query) {
            calls++;
            this.query = query;
            return new ExecutionPlan.Rows(result.schema(), result);
        }
    }

    private static ResultRow row(int nim, Value ipk) {
        return new ResultRow(List.of(Value.ofInt(nim), ipk), null);
    }

    // SELECT n.nim FROM nilai n WHERE <where>
    private static SelectQuery query(Expr where) {
        return new SelectQuery(
                "SELECT n.nim FROM nilai n",
                List.of(N_NIM),
                new FromItem.TableRef("nilai", "n"),
                where,
                List.of());
    }

    private static SubqueryEvaluator evaluator(FakeRunner runner) {
        SubqueryEvaluator evaluator = new SubqueryEvaluator(new DefaultExpressionEvaluator());
        evaluator.setRunner(runner);
        return evaluator;
    }

    private static TruthValue truth(Expr condition, ResultRow row, FakeRunner runner) {
        return evaluator(runner).evaluateCondition(condition, row, OUTER);
    }

    private static TruthValue in(Value value, FakeRunner runner) {
        return truth(new Expr.InSubquery(new Expr.Literal(value), QUERY), ROW, runner);
    }

    // EXISTS (SELECT n.nim FROM nilai n WHERE n.nim = outer)
    private static Expr outerExists(Expr.ColumnRef outer) {
        return new Expr.ExistsSubquery(query(new Expr.Binary(Expr.Op.EQ, N_NIM, outer)));
    }

    // SELECT n.nim FROM nilai n JOIN kelas k ON k.id = outer, dosen d
    // WHERE NOT n.nim <> outer
    //   AND EXISTS (SELECT y.nim FROM y WHERE y.nim = n.nim AND y.nim > deeper)
    // ORDER BY outer
    private static SelectQuery correlated(Expr outer, Expr deeper) {
        Expr.ColumnRef yNim = new Expr.ColumnRef("y", "nim", 0);
        Expr nestedWhere =
                new Expr.Binary(
                        Expr.Op.AND,
                        new Expr.Binary(Expr.Op.EQ, yNim, new Expr.ColumnRef("n", "nim", 0, 1)),
                        new Expr.Binary(Expr.Op.GT, yNim, deeper));
        SelectQuery nested =
                new SelectQuery(
                        "SELECT y.nim FROM y",
                        List.of(yNim),
                        new FromItem.TableRef("y"),
                        nestedWhere,
                        List.of());
        FromItem join =
                new FromItem.Join(
                        new FromItem.TableRef("nilai", "n"),
                        new FromItem.TableRef("kelas", "k"),
                        new Expr.Binary(Expr.Op.EQ, new Expr.ColumnRef("k", "id", 1), outer));
        FromItem from = new FromItem.CartesianProduct(join, new FromItem.TableRef("dosen", "d"));
        Expr where =
                new Expr.Binary(
                        Expr.Op.AND,
                        new Expr.Not(new Expr.Binary(Expr.Op.NE, N_NIM, outer)),
                        new Expr.ExistsSubquery(nested));
        return new SelectQuery(
                "SELECT n.nim FROM nilai n JOIN kelas k, dosen d",
                List.of(N_NIM),
                from,
                where,
                List.of(new OrderByItem(outer, true)));
    }

    @Test
    void inSubqueryFollowsThreeValuedLogic() {
        FakeRunner found = new FakeRunner(DataType.INTEGER, ONE, TWO);
        assertEquals(TruthValue.TRUE, in(TWO, found));
        assertEquals(QUERY, found.query);
        assertEquals(1, found.calls);
        assertEquals(1, found.result.getCloseCount());

        assertEquals(TruthValue.FALSE, in(TWO, new FakeRunner(DataType.INTEGER, ONE)));
        assertEquals(TruthValue.UNKNOWN, in(TWO, new FakeRunner(DataType.INTEGER, ONE, NULL_INT)));
        assertEquals(TruthValue.TRUE, in(TWO, new FakeRunner(DataType.INTEGER, NULL_INT, TWO)));
        assertEquals(TruthValue.FALSE, in(NULL_INT, new FakeRunner(DataType.INTEGER)));
        assertEquals(TruthValue.UNKNOWN, in(NULL_INT, new FakeRunner(DataType.INTEGER, ONE)));
        assertEquals(TruthValue.TRUE, in(TWO, new FakeRunner(DataType.FLOAT, Value.ofFloat(2.0f))));
    }

    @Test
    void existsChecksRowsAndCloses() {
        Expr exists = new Expr.ExistsSubquery(QUERY);
        FakeRunner some = new FakeRunner(DataType.INTEGER, ONE, TWO);
        FakeRunner none = new FakeRunner(DataType.INTEGER);
        assertEquals(TruthValue.TRUE, truth(exists, ROW, some));
        assertEquals(TruthValue.FALSE, truth(exists, ROW, none));
        assertEquals(1, some.result.getCloseCount());
        assertEquals(1, none.result.getCloseCount());

        FakeRunner broken = new FakeRunner(DataType.INTEGER, ONE);
        broken.result.failNextOpen();
        assertThrows(RuntimeException.class, () -> truth(exists, ROW, broken));
        assertEquals(1, broken.result.getCloseCount());
    }

    @Test
    void scalarSubqueryInComparison() {
        // m.nim = (SELECT n.nim FROM nilai n)
        Expr condition = new Expr.Binary(Expr.Op.EQ, NIM, new Expr.ScalarSubquery(QUERY));
        assertEquals(TruthValue.TRUE, truth(condition, ROW, new FakeRunner(DataType.INTEGER, TWO)));
        assertEquals(
                TruthValue.FALSE, truth(condition, ROW, new FakeRunner(DataType.INTEGER, THREE)));
        assertEquals(TruthValue.UNKNOWN, truth(condition, ROW, new FakeRunner(DataType.INTEGER)));

        FakeRunner many = new FakeRunner(DataType.INTEGER, TWO, THREE);
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> truth(condition, ROW, many));
        assertEquals("Scalar subquery returned more than one row", error.getMessage());
        assertEquals(1, many.result.getCloseCount());

        // m.ipk > (SELECT n.nim FROM nilai n) * 2
        Expr doubled =
                new Expr.Binary(Expr.Op.MUL, new Expr.ScalarSubquery(QUERY), new Expr.Literal(TWO));
        Expr arithmetic = new Expr.Binary(Expr.Op.GT, IPK, doubled);
        assertEquals(
                TruthValue.TRUE,
                truth(arithmetic, ROW, new FakeRunner(DataType.FLOAT, Value.ofFloat(1.5f))));
        SubqueryEvaluator evaluator = evaluator(new FakeRunner(DataType.INTEGER, THREE));
        assertEquals(Value.ofInt(6), evaluator.evaluate(doubled, ROW, OUTER));
    }

    @Test
    void subqueryInsideAndUsesThreeValuedLogic() {
        // m.ipk > 3.0 AND m.nim IN (SELECT n.nim FROM nilai n)
        Expr condition = new Expr.Binary(Expr.Op.AND, IPK_ABOVE_3, new Expr.InSubquery(NIM, QUERY));
        assertEquals(
                TruthValue.TRUE, truth(condition, ROW, new FakeRunner(DataType.INTEGER, ONE, TWO)));
        assertEquals(
                TruthValue.FALSE, truth(condition, ROW, new FakeRunner(DataType.INTEGER, ONE)));
        assertEquals(
                TruthValue.UNKNOWN,
                truth(condition, ROW, new FakeRunner(DataType.INTEGER, ONE, NULL_INT)));

        ResultRow unknownIpk = row(2, Value.nullOf(DataType.FLOAT));
        assertEquals(
                TruthValue.UNKNOWN,
                truth(condition, unknownIpk, new FakeRunner(DataType.INTEGER, TWO)));

        FakeRunner skipped = new FakeRunner(DataType.INTEGER, TWO);
        assertEquals(TruthValue.FALSE, truth(condition, row(2, Value.ofFloat(2.0f)), skipped));
        assertEquals(0, skipped.calls);
    }

    @Test
    void notAndOrWithSubqueriesUseThreeValuedLogic() {
        // NOT m.nim IN (SELECT n.nim FROM nilai n)
        Expr notIn = new Expr.Not(new Expr.InSubquery(NIM, QUERY));
        assertEquals(
                TruthValue.FALSE, truth(notIn, ROW, new FakeRunner(DataType.INTEGER, ONE, TWO)));
        assertEquals(TruthValue.TRUE, truth(notIn, ROW, new FakeRunner(DataType.INTEGER, ONE)));
        assertEquals(
                TruthValue.UNKNOWN,
                truth(notIn, ROW, new FakeRunner(DataType.INTEGER, ONE, NULL_INT)));

        // m.ipk > 3.0 OR m.nim IN (SELECT n.nim FROM nilai n)
        Expr or = new Expr.Binary(Expr.Op.OR, IPK_ABOVE_3, new Expr.InSubquery(NIM, QUERY));
        FakeRunner skipped = new FakeRunner(DataType.INTEGER, ONE);
        assertEquals(TruthValue.TRUE, truth(or, ROW, skipped));
        assertEquals(0, skipped.calls);

        ResultRow lowIpk = row(2, Value.ofFloat(2.0f));
        assertEquals(TruthValue.TRUE, truth(or, lowIpk, new FakeRunner(DataType.INTEGER, TWO)));
        assertEquals(TruthValue.FALSE, truth(or, lowIpk, new FakeRunner(DataType.INTEGER, ONE)));
        assertEquals(
                TruthValue.UNKNOWN,
                truth(or, lowIpk, new FakeRunner(DataType.INTEGER, ONE, NULL_INT)));
    }

    @Test
    void outerColumnsBecomeLiteralsFoundByName() {
        // Offset 0 sengaja nunjuk dosen.nim, yang bener m.nim di posisi 1.
        List<ColumnSchema> schema =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("dosen", "nim"), DataType.INTEGER),
                        new ColumnSchema(new Expr.ColumnRef("m", "nim"), DataType.INTEGER));
        ResultRow row = new ResultRow(List.of(Value.ofInt(9), TWO), null);
        SelectQuery query =
                correlated(
                        new Expr.ColumnRef("M", "NIM", 0, 1), new Expr.ColumnRef("m", "nim", 0, 2));
        FakeRunner runner = new FakeRunner(DataType.INTEGER, TWO);

        SubqueryEvaluator evaluator = evaluator(runner);
        Expr exists = new Expr.ExistsSubquery(query);
        assertEquals(TruthValue.TRUE, evaluator.evaluateCondition(exists, row, schema));
        assertEquals(correlated(new Expr.Literal(TWO), new Expr.Literal(TWO)), runner.query);
    }

    @Test
    void badOuterColumnsFailBeforeTheRunner() {
        FakeRunner runner = new FakeRunner(DataType.INTEGER, ONE);
        Expr missing = outerExists(new Expr.ColumnRef("x", "nim", 0, 1));
        Expr noTable = outerExists(new Expr.ColumnRef(null, "nim", 0, 1));
        Expr tooDeep = outerExists(new Expr.ColumnRef("m", "nim", 0, 2));

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> truth(missing, ROW, runner));
        assertEquals("Outer column not found: x.nim", error.getMessage());
        error = assertThrows(IllegalArgumentException.class, () -> truth(noTable, ROW, runner));
        assertEquals("Outer column not found: null.nim", error.getMessage());
        error = assertThrows(IllegalArgumentException.class, () -> truth(tooDeep, ROW, runner));
        assertEquals("Correlated column is out of scope", error.getMessage());
        assertEquals(0, runner.calls);
    }

    @Test
    void withoutSubqueryMatchesTheDefaultEvaluator() {
        DefaultExpressionEvaluator plain = new DefaultExpressionEvaluator();
        SubqueryEvaluator evaluator = new SubqueryEvaluator(plain);
        List<ResultRow> rows =
                List.of(ROW, row(1, Value.ofFloat(2.0f)), row(3, Value.nullOf(DataType.FLOAT)));
        for (ResultRow row : rows) {
            assertEquals(
                    plain.evaluateCondition(IPK_ABOVE_3, row, OUTER),
                    evaluator.evaluateCondition(IPK_ABOVE_3, row, OUTER));
        }
        Expr sum = new Expr.Binary(Expr.Op.ADD, NIM, new Expr.Literal(ONE));
        assertEquals(plain.evaluate(sum, ROW, OUTER), evaluator.evaluate(sum, ROW, OUTER));

        Expr exists = new Expr.ExistsSubquery(QUERY);
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> evaluator.evaluateCondition(exists, ROW, OUTER));
        assertEquals("Subquery runner is not set", error.getMessage());
        ResultRow shortRow = new ResultRow(List.of(ONE), null);
        assertThrows(
                IllegalArgumentException.class,
                () -> truth(exists, shortRow, new FakeRunner(DataType.INTEGER, ONE)));
    }

    @Test
    void filterRerunsSubqueryPerRow() {
        ResultRow first = row(1, Value.ofFloat(3.5f));
        ResultRow second = row(2, Value.ofFloat(3.5f));
        ResultRow third = row(3, Value.ofFloat(3.5f));
        FakeChildOp child = new FakeChildOp(OUTER, List.of(first, second, third));
        FakeRunner runner = new FakeRunner(DataType.INTEGER, TWO, THREE);
        FilterOp filter = new FilterOp(child, new Expr.InSubquery(NIM, QUERY), evaluator(runner));

        filter.open();
        assertSame(second, filter.next());
        assertSame(third, filter.next());
        assertNull(filter.next());
        filter.close();
        assertEquals(3, runner.calls);
        assertEquals(3, runner.result.getCloseCount());
    }
}
