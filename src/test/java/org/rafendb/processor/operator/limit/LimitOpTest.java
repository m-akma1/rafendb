package org.rafendb.processor.operator.limit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.SortPlanNode.Direction;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.operator.sort.SortOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class LimitOpTest {
    private static final List<ColumnSchema> SCHEMA =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "nim"), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef("mahasiswa", "ipk"), DataType.FLOAT));

    private static ResultRow row(int nim, float ipk, int slot) {
        return new ResultRow(List.of(Value.ofInt(nim), Value.ofFloat(ipk)), new Rid(1, slot));
    }

    @Test
    void returnsOnlyFirstRowsUpToLimit() {
        ResultRow first = row(1, 3.5f, 0);
        ResultRow second = row(2, 2.0f, 1);
        ResultRow third = row(3, 3.9f, 2);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(first, second, third));
        LimitOp limit = new LimitOp(child, 2);
        limit.open();
        assertSame(first, limit.next());
        assertSame(second, limit.next());
        assertNull(limit.next());
        assertNull(limit.next());
        limit.close();
    }

    @Test
    void returnsAllRowsWhenLimitIsAtLeastRowCount() {
        ResultRow first = row(1, 3.5f, 0);
        ResultRow second = row(2, 2.0f, 1);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(first, second));
        for (int n = 2; n <= 3; n++) {
            LimitOp limit = new LimitOp(child, n);
            limit.open();
            assertSame(first, limit.next());
            assertSame(second, limit.next());
            assertNull(limit.next());
            assertNull(limit.next());
            limit.close();
        }
    }

    @Test
    void limitZeroDoesNotReadChild() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        LimitOp limit = new LimitOp(child, 0);
        limit.open();
        assertNull(limit.next());
        assertNull(limit.next());
        assertEquals(0, child.getNextCount());
        limit.close();
    }

    @Test
    void reopenStartsFromBeginning() {
        ResultRow first = row(1, 3.5f, 0);
        ResultRow second = row(2, 3.6f, 1);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(first, second));
        LimitOp limit = new LimitOp(child, 1);
        limit.open();
        assertSame(first, limit.next());
        assertNull(limit.next());
        limit.open();
        assertSame(first, limit.next());
        assertNull(limit.next());

        limit.close();
        limit.open();
        assertSame(first, limit.next());
        limit.close();
    }

    @Test
    void nextThrowsWhenNotOpenAndCloseIsSafe() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        LimitOp limit = new LimitOp(child, 1);
        assertThrows(IllegalStateException.class, limit::next);
        limit.close();
        assertEquals(1, child.getCloseCount());

        limit.open();
        limit.close();
        assertEquals(2, child.getCloseCount());
        assertThrows(IllegalStateException.class, limit::next);

        limit.close();
        assertEquals(3, child.getCloseCount());
    }

    @Test
    void childOpenFailureIsPropagatedAndLimitStaysClosed() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1, 3.5f, 0)));
        LimitOp limit = new LimitOp(child, 1);
        limit.open();
        child.failNextOpen();
        RuntimeException openError = assertThrows(RuntimeException.class, limit::open);
        assertEquals("Child open failed", openError.getMessage());

        IllegalStateException nextError = assertThrows(IllegalStateException.class, limit::next);
        assertEquals("LimitOp is not open", nextError.getMessage());
        limit.close();
    }

    @Test
    void schemaIsAlwaysAvailable() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        LimitOp limit = new LimitOp(child, 1);
        assertEquals(SCHEMA, limit.schema());

        limit.open();
        assertNull(limit.next());
        assertEquals(SCHEMA, limit.schema());

        limit.close();
        assertEquals(SCHEMA, limit.schema());
    }

    @Test
    void invalidConstructorArgumentsAreRejected() {
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of());
        assertThrows(NullPointerException.class, () -> new LimitOp(null, 1));
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> new LimitOp(child, -1));
        assertEquals("Negative limit", error.getMessage());
    }

    @Test
    void wrapAddsLimitOnlyWhenGiven() {
        ResultRow first = row(1, 3.5f, 0);
        ResultRow second = row(2, 2.0f, 1);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(first, second));
        ExecutionPlan.Rows rows = new ExecutionPlan.Rows(SCHEMA, child);
        assertSame(rows, LimitOp.wrap(rows, null));

        ExecutionPlan.Rows limited = LimitOp.wrap(rows, 1);
        assertEquals(SCHEMA, limited.columns());
        Operator operator = limited.operator();
        operator.open();
        assertSame(first, operator.next());
        assertNull(operator.next());
        operator.close();

        assertThrows(NullPointerException.class, () -> LimitOp.wrap(null, 1));
    }

    @Test
    void limitAfterSortReturnsTopRows() {
        ResultRow ani = row(1, 3.2f, 0);
        ResultRow budi = row(2, 3.9f, 1);
        ResultRow cici = row(3, 2.8f, 2);
        ResultRow dodi = row(4, 3.5f, 3);
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(ani, budi, cici, dodi));
        LimitOp limit = new LimitOp(new SortOp(child, 1, Direction.DESC), 2);
        limit.open();
        assertSame(budi, limit.next());
        assertSame(dodi, limit.next());
        assertNull(limit.next());
        limit.close();
    }
}
