package org.rafendb.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.*;
import org.rafendb.common.plan.SortPlanNode.Direction;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.*;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.TruthValue;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.sort.SortOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class Qp4CompatibilityTest {
    private final ColumnSchema a =
            new ColumnSchema(new Expr.ColumnRef("a", "id"), DataType.INTEGER);
    private final ColumnSchema b =
            new ColumnSchema(new Expr.ColumnRef("b", "id"), DataType.INTEGER);

    @Test
    void concatenationPreservesPositionsAndIdentities() {
        var outer = new ResultRow(List.of(Value.ofInt(1)), new Rid(0, 0));
        var inner = new ResultRow(List.of(Value.nullOf(DataType.INTEGER)), new Rid(1, 0));
        var joined = ResultRow.concat(outer, inner);
        assertNull(joined.rid());
        assertEquals(new Rid(0, 0), outer.rid());
        assertEquals(new Rid(1, 0), inner.rid());
        assertEquals(2, joined.size());
        assertEquals(outer.get(0), joined.get(0));
        assertEquals(Value.nullOf(DataType.INTEGER), joined.get(1));
        assertEquals(1, outer.size());
        assertEquals(1, inner.size());
        assertThrows(IndexOutOfBoundsException.class, () -> joined.get(2));
        assertThrows(UnsupportedOperationException.class, () -> joined.values().clear());
    }

    @Test
    void cursorResetsMidReadAfterExhaustionAndAfterClose() {
        var first = new ResultRow(List.of(Value.ofInt(1)), null);
        var second = new ResultRow(List.of(Value.ofInt(2)), null);
        var cursor =
                new SortOp(new FakeChildOp(List.of(b), List.of(second, first)), 0, Direction.ASC);
        assertEquals(List.of(b), cursor.schema());
        assertThrows(IllegalStateException.class, cursor::next);
        cursor.close();
        cursor.close();
        cursor.open();
        assertEquals(first, cursor.next());
        cursor.open();
        assertEquals(first, cursor.next());
        assertEquals(second, cursor.next());
        assertNull(cursor.next());
        assertNull(cursor.next());
        cursor.open();
        assertEquals(first, cursor.next());
        cursor.close();
        assertThrows(IllegalStateException.class, cursor::next);
        cursor.close();
        cursor.open();
        assertEquals(first, cursor.next());
        cursor.close();
        assertEquals(List.of(b), cursor.schema());
    }

    @Test
    void emptySchemaAndHeaderConsistencyDoNotDependOnRows() {
        var cursor = new SortOp(new FakeChildOp(List.of(a), List.of()), 0, Direction.ASC);
        var result = new ExecutionPlan.Rows(List.of(a), cursor);
        assertEquals(List.of(a), result.columns());
        assertEquals(List.of(a), cursor.schema());
        cursor.open();
        assertNull(cursor.next());
        assertThrows(UnsupportedOperationException.class, () -> cursor.schema().clear());
        assertThrows(
                IllegalArgumentException.class, () -> new ExecutionPlan.Rows(List.of(b), cursor));
        cursor.close();
    }

    @Test
    void unknownIsObservableButOnlyTruePasses() {
        var evaluator = new DefaultExpressionEvaluator();
        Expr predicate =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("a", "id", 0),
                        new Expr.ColumnRef("b", "id", 1));
        var schema = List.of(a, b);
        var rightValues = List.of(Value.ofInt(1), Value.ofInt(2), Value.nullOf(DataType.INTEGER));
        var expected = List.of(TruthValue.TRUE, TruthValue.FALSE, TruthValue.UNKNOWN);
        for (int i = 0; i < rightValues.size(); i++) {
            var row =
                    ResultRow.concat(
                            new ResultRow(List.of(Value.ofInt(1)), null),
                            new ResultRow(List.of(rightValues.get(i)), null));
            assertEquals(expected.get(i), evaluator.evaluateCondition(predicate, row, schema));
            assertEquals(
                    expected.get(i) == TruthValue.TRUE, evaluator.test(predicate, row, schema));
        }
    }

    @Test
    void storageValuesPreserveTypesAndRequireQueryLevelSortRules() {
        var integer = Value.ofInt(2);
        var floating = Value.ofFloat(2.0f);
        var sqlNull = Value.nullOf(DataType.INTEGER);
        assertEquals(DataType.INTEGER, integer.type());
        assertEquals(2, integer.asInt());
        assertEquals(DataType.FLOAT, floating.type());
        assertEquals(2.0f, floating.asFloat());
        assertEquals(0, Value.ofFloat(-0.0f).compareTo(Value.ofFloat(0.0f)));
        assertTrue(sqlNull.isNull());
        assertEquals(DataType.INTEGER, sqlNull.type());
        assertNotEquals(integer, floating);
        assertThrows(IllegalArgumentException.class, () -> integer.compareTo(floating));
        assertThrows(IllegalArgumentException.class, () -> sqlNull.compareTo(integer));
        assertThrows(IllegalArgumentException.class, () -> integer.compareTo(sqlNull));
        assertThrows(IllegalStateException.class, sqlNull::asInt);
        assertThrows(IllegalArgumentException.class, () -> new Value(DataType.FLOAT, 2.0));
    }

    @Test
    void storageTextComparisonIsNotTheQp4CodePointSortContract() {
        var text = Value.ofText(DataType.VARCHAR, "abc");
        assertEquals("abc", text.asText());
        assertEquals(text, Value.ofText(DataType.CHAR, "abc"));
        assertTrue(
                Value.ofText(DataType.CHAR, "A").compareTo(Value.ofText(DataType.VARCHAR, "a"))
                        < 0);
        assertTrue(
                Value.ofText(DataType.VARCHAR, "\uE000")
                                .compareTo(Value.ofText(DataType.VARCHAR, "\uD800\uDC00"))
                        > 0);
        assertThrows(IllegalArgumentException.class, () -> text.compareTo(Value.ofInt(2)));
        assertThrows(NullPointerException.class, () -> text.compareTo(null));
        assertThrows(IllegalArgumentException.class, () -> Value.ofText(DataType.INTEGER, "2"));
    }
}
