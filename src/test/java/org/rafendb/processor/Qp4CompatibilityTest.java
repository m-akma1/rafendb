package org.rafendb.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.api.processor.ExecutionPlan;
import org.rafendb.common.expr.*;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.*;
import org.rafendb.mock.processor.*;

class Qp4CompatibilityTest {
    private final ColumnRef a = new ColumnRef("a", "id");
    private final ColumnRef b = new ColumnRef("b", "id");

    @Test
    void concatenationPreservesPositionsAndIdentities() {
        var outer = new Row(List.of(a), List.of(new Value.IntegerValue(1)));
        var inner = new Row(List.of(b), List.of(Value.NullValue.INSTANCE));
        var joined = Row.concat(outer, inner);
        assertEquals(List.of(a, b), joined.columns());
        assertEquals(2, joined.size());
        assertEquals(outer.get(0), joined.get(0));
        assertSame(Value.NullValue.INSTANCE, joined.get(1));
        assertEquals(1, outer.size());
        assertEquals(1, inner.size());
        assertThrows(IndexOutOfBoundsException.class, () -> joined.get(2));
        assertThrows(UnsupportedOperationException.class, () -> joined.values().clear());
    }

    @Test
    void cursorResetsMidReadAfterExhaustionAndAfterClose() {
        var first = new Row(List.of(b), List.of(new Value.IntegerValue(1)));
        var second = new Row(List.of(b), List.of(new Value.IntegerValue(2)));
        var cursor = new MockOperator(List.of(b), List.of(first, second));
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
        var cursor = new MockOperator(List.of(a), List.of());
        assertEquals(List.of(a), cursor.schema());
        cursor.open();
        assertNull(cursor.next());
        assertThrows(UnsupportedOperationException.class, () -> cursor.schema().clear());
        assertThrows(
                IllegalArgumentException.class, () -> new ExecutionPlan.Rows(List.of(b), cursor));
        var wrong = new Row(List.of(b), List.of(new Value.IntegerValue(1)));
        assertThrows(
                IllegalArgumentException.class, () -> new MockOperator(List.of(a), List.of(wrong)));
        cursor.close();
    }

    @Test
    void unknownIsObservableButOnlyTruePasses() {
        var literal = new Expression.Literal(Value.NullValue.INSTANCE);
        var predicate = new Predicate.Comparison(literal, Predicate.ComparisonOp.EQ, literal);
        var row = new Row(List.of(), List.of());
        for (var truth : TruthValue.values()) {
            var mock = new MockExpressionEvaluator(Value.NullValue.INSTANCE, truth);
            assertEquals(truth, mock.evaluateCondition(predicate, row));
            assertEquals(truth == TruthValue.TRUE, mock.test(predicate, row));
            assertSame(Value.NullValue.INSTANCE, mock.evaluate(literal, row));
        }
        // A mock returns configured truth; it does not infer NULL semantics from the tree.
        assertTrue(
                new MockExpressionEvaluator(new Value.IntegerValue(0), true).test(predicate, row));
    }

    @Test
    void numericOrderHandlesNullMixedNumbersAndSignedZero() {
        var values =
                new ArrayList<Value>(
                        List.of(
                                new Value.IntegerValue(10),
                                new Value.FloatValue(2.5),
                                Value.NullValue.INSTANCE,
                                new Value.IntegerValue(2)));
        values.sort(Comparator.naturalOrder());
        assertEquals(
                List.of(
                        Value.NullValue.INSTANCE,
                        new Value.IntegerValue(2),
                        new Value.FloatValue(2.5),
                        new Value.IntegerValue(10)),
                values);
        values.sort(Comparator.reverseOrder());
        assertSame(Value.NullValue.INSTANCE, values.getLast());
        assertEquals(0, new Value.IntegerValue(2).compareTo(new Value.FloatValue(2.0)));
        assertEquals(0, new Value.FloatValue(-0.0).compareTo(new Value.IntegerValue(0)));
        assertEquals(0, Value.NullValue.INSTANCE.compareTo(Value.NullValue.INSTANCE));
    }

    @Test
    void textOrderUsesCodePointsAndRejectsMixedTypes() {
        assertTrue(
                new Value.TextValue("\uE000").compareTo(new Value.TextValue("\uD800\uDC00")) < 0);
        assertTrue(new Value.TextValue("A").compareTo(new Value.TextValue("a")) < 0);
        assertTrue(new Value.TextValue("a").compareTo(new Value.TextValue("aa")) < 0);
        assertEquals(0, new Value.TextValue("abc").compareTo(new Value.TextValue("abc")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Value.TextValue("2").compareTo(new Value.IntegerValue(2)));
        assertThrows(NullPointerException.class, () -> new Value.IntegerValue(2).compareTo(null));
        assertThrows(IllegalArgumentException.class, () -> new Value.FloatValue(Double.NaN));
    }
}
