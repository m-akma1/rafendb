package org.rafendb.processor.operator.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.expr.TruthValue;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class FilterOpTest {
    private static final List<ColumnSchema> SCHEMA =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT));

    /** ipk > 3.0 */
    private static final Expr CONDITION =
            new Expr.Binary(
                    Expr.Op.GT,
                    new Expr.ColumnRef("mahasiswa", "ipk", 1),
                    new Expr.Literal(Value.ofFloat(3.0f)));

    /**
     * Evaluator palsu: ipk > 3.0, ipk NULL jadi UNKNOWN. Mock QP2 hasilnya selalu sama, jadi ga
     * bisa buat ngetes "lolos sebagian". test() sengaja ga di-override biar lewat default method.
     */
    private static class FakeEvaluator implements ExpressionEvaluator {
        boolean throwError;
        Expr lastCondition;
        List<ColumnSchema> lastSchema;

        @Override
        public Value evaluate(Expr expression, ResultRow row, List<ColumnSchema> schema) {
            throw new UnsupportedOperationException("Not used by FilterOp");
        }

        @Override
        public TruthValue evaluateCondition(
                Expr condition, ResultRow row, List<ColumnSchema> schema) {
            if (throwError) {
                throw new IllegalArgumentException("Unknown column");
            }
            lastCondition = condition;
            lastSchema = schema;
            Value ipk = row.get(1);
            if (ipk.isNull()) {
                return TruthValue.UNKNOWN;
            }
            if (ipk.asFloat() > 3.0f) {
                return TruthValue.TRUE;
            }
            return TruthValue.FALSE;
        }
    }

    private final FakeEvaluator evaluator = new FakeEvaluator();

    private static ResultRow row(int nim, float ipk, int slot) {
        return new ResultRow(List.of(Value.ofInt(nim), Value.ofFloat(ipk)), new Rid(1, slot));
    }

    @Test
    void passesOnlyRowsWhereConditionIsTrue() {
        ResultRow first = row(1, 3.5f, 0);
        ResultRow second = row(2, 2.0f, 1);
        ResultRow third = row(3, 3.9f, 2);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(first, second, third));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        assertSame(first, filter.next());
        assertSame(third, filter.next());
        assertNull(filter.next());
    }

    @Test
    void returnsNullWhenNoRowMatches() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 1.0f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        assertNull(filter.next());
    }

    @Test
    void keepsReturningNullAfterExhausted() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        filter.next();
        assertNull(filter.next());
        assertNull(filter.next());
    }

    @Test
    void sqlNullDoesNotPass() {
        ResultRow nullIpk =
                new ResultRow(List.of(Value.ofInt(1), Value.nullOf(DataType.FLOAT)), new Rid(1, 0));
        ResultRow passing = row(2, 3.5f, 1);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(nullIpk, passing));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        assertSame(passing, filter.next());
        assertNull(filter.next());
    }

    @Test
    void passingRowKeepsRid() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 7)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        assertEquals(new Rid(1, 7), filter.next().rid());
    }

    @Test
    void evaluatorReceivesConditionAndChildSchema() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        filter.next();
        assertSame(CONDITION, evaluator.lastCondition);
        assertEquals(SCHEMA, evaluator.lastSchema);
    }

    @Test
    void childSchemaIsTakenOnlyOnceAtOpen() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0), row(2, 1.0f, 1)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        assertEquals(1, child.getSchemaCount());
        filter.next();
        filter.next();
        assertEquals(1, child.getSchemaCount());
    }

    @Test
    void reopenStartsFromBeginning() {
        ResultRow first = row(1, 3.5f, 0);
        ResultRow second = row(2, 3.6f, 1);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(first, second));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        filter.next();
        filter.open();
        assertSame(first, filter.next());
        assertSame(second, filter.next());
        assertNull(filter.next());

        filter.close();
        filter.open();
        assertSame(first, filter.next());
    }

    @Test
    void worksWithRealEvaluator() {
        ResultRow passing = row(1, 3.5f, 0);
        ResultRow failing = row(2, 2.0f, 1);
        ResultRow nullIpk =
                new ResultRow(List.of(Value.ofInt(3), Value.nullOf(DataType.FLOAT)), new Rid(1, 2));
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(passing, failing, nullIpk));
        FilterOp filter = new FilterOp(child, CONDITION, new DefaultExpressionEvaluator());
        filter.open();
        assertSame(passing, filter.next());
        assertNull(filter.next());
    }

    @Test
    void childOpenFailureIsPropagatedAndFilterStaysClosed() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        child.failNextOpen();
        RuntimeException openError = assertThrows(RuntimeException.class, filter::open);
        assertEquals("Child open failed", openError.getMessage());

        IllegalStateException nextError = assertThrows(IllegalStateException.class, filter::next);
        assertEquals("FilterOp is not open", nextError.getMessage());

        filter.close();
        assertEquals(1, child.getCloseCount());
    }

    @Test
    void nextAfterFailedReopenThrows() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.open();
        child.failNextOpen();
        assertThrows(RuntimeException.class, filter::open);

        IllegalStateException error = assertThrows(IllegalStateException.class, filter::next);
        assertEquals("FilterOp is not open", error.getMessage());
    }

    @Test
    void nextThrowsWhenNotOpen() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        assertThrows(IllegalStateException.class, filter::next);

        filter.open();
        filter.close();
        assertThrows(IllegalStateException.class, filter::next);
    }

    @Test
    void closeIsSafeBeforeOpenAndWhenRepeated() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        filter.close();
        assertEquals(1, child.getCloseCount());

        filter.open();
        filter.close();
        assertEquals(2, child.getCloseCount());

        filter.close();
        assertEquals(3, child.getCloseCount());
    }

    @Test
    void schemaIsAlwaysAvailable() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        assertEquals(SCHEMA, filter.schema());

        filter.open();
        assertNull(filter.next());
        assertEquals(SCHEMA, filter.schema());

        filter.close();
        assertEquals(SCHEMA, filter.schema());
    }

    @Test
    void evaluatorErrorIsPropagated() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        FilterOp filter = new FilterOp(child, CONDITION, evaluator);
        evaluator.throwError = true;
        filter.open();
        assertThrows(IllegalArgumentException.class, filter::next);
    }

    @Test
    void nullConstructorArgumentsAreRejected() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        assertThrows(NullPointerException.class, () -> new FilterOp(null, CONDITION, evaluator));
        assertThrows(NullPointerException.class, () -> new FilterOp(child, null, evaluator));
        assertThrows(NullPointerException.class, () -> new FilterOp(child, CONDITION, null));
    }
}
