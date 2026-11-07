package org.rafendb.processor.expr;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class DefaultExpressionEvaluatorTest {
    private final DefaultExpressionEvaluator evaluator = new DefaultExpressionEvaluator();
    private final ResultRow empty = new ResultRow(List.of(), null);

    private static Expr literal(Value value) {
        return new Expr.Literal(value);
    }

    private static Expr integer(int value) {
        return literal(Value.ofInt(value));
    }

    private static Expr binary(Expr.Op op, Expr left, Expr right) {
        return new Expr.Binary(op, left, right);
    }

    private Value scalar(Expr expression) {
        return evaluator.evaluate(expression, empty, List.of());
    }

    private TruthValue truth(Expr expression) {
        return evaluator.evaluateCondition(expression, empty, List.of());
    }

    private static Expr predicate(TruthValue value) {
        return binary(
                Expr.Op.EQ,
                value == TruthValue.UNKNOWN ? literal(Value.nullOf(DataType.INTEGER)) : integer(1),
                integer(value == TruthValue.FALSE ? 2 : 1));
    }

    @Test
    void readsEveryScalarTypeAndTypedNull() {
        for (Value value :
                List.of(
                        Value.ofInt(7),
                        Value.ofFloat(1.5f),
                        Value.ofText(DataType.CHAR, "a"),
                        Value.ofText(DataType.VARCHAR, "b"),
                        Value.nullOf(DataType.FLOAT))) {
            assertEquals(value, scalar(literal(value)));
            var ref = new Expr.ColumnRef("t", "c", 0);
            assertEquals(
                    value,
                    evaluator.evaluate(
                            ref,
                            new ResultRow(List.of(value), null),
                            List.of(new ColumnSchema(ref, value.type()))));
        }
    }

    @Test
    void computesIntegerOperationsAndNestedExpressions() {
        assertEquals(Value.ofInt(10), scalar(binary(Expr.Op.ADD, integer(7), integer(3))));
        assertEquals(Value.ofInt(4), scalar(binary(Expr.Op.SUB, integer(7), integer(3))));
        assertEquals(Value.ofInt(21), scalar(binary(Expr.Op.MUL, integer(7), integer(3))));
        assertEquals(Value.ofInt(-2), scalar(binary(Expr.Op.DIV, integer(-7), integer(3))));
        assertEquals(Value.ofInt(1), scalar(binary(Expr.Op.MOD, integer(7), integer(3))));
        assertEquals(
                Value.ofInt(20),
                scalar(
                        binary(
                                Expr.Op.MUL,
                                binary(Expr.Op.ADD, integer(2), integer(3)),
                                integer(4))));
    }

    @Test
    void promotesMixedArithmeticAndPreservesFloatResults() {
        Expr half = literal(Value.ofFloat(0.5f));
        assertEquals(Value.ofFloat(2.5f), scalar(binary(Expr.Op.ADD, integer(2), half)));
        assertEquals(Value.ofFloat(1.5f), scalar(binary(Expr.Op.SUB, integer(2), half)));
        assertEquals(Value.ofFloat(1), scalar(binary(Expr.Op.MUL, half, integer(2))));
        assertEquals(Value.ofFloat(4), scalar(binary(Expr.Op.DIV, integer(2), half)));
        assertEquals(
                Value.ofFloat(0.5f),
                scalar(binary(Expr.Op.MOD, literal(Value.ofFloat(2.5f)), integer(2))));
    }

    @Test
    void evaluatesSalaryExampleUsingOffsetAfterColumnReordering() {
        var salary = new Expr.ColumnRef("p", "salary", 1);
        var schema =
                List.of(
                        new ColumnSchema(new Expr.ColumnRef("p", "id", 0), DataType.INTEGER),
                        new ColumnSchema(salary, DataType.INTEGER));
        var row = new ResultRow(List.of(Value.ofInt(99), Value.ofInt(6000)), null);
        Expr expression = binary(Expr.Op.MUL, salary, literal(Value.ofFloat(1.05f)));
        assertEquals(6300f, evaluator.evaluate(expression, row, schema).asFloat(), 0.001f);
        assertTrue(evaluator.test(binary(Expr.Op.GT, expression, integer(5000)), row, schema));
    }

    @Test
    void distinguishesSelfJoinColumnsByOffset() {
        var outer = new Expr.ColumnRef("p", "id", 0);
        var inner = new Expr.ColumnRef("m", "id", 1);
        var schema =
                List.of(
                        new ColumnSchema(outer, DataType.INTEGER),
                        new ColumnSchema(inner, DataType.INTEGER));
        var row = new ResultRow(List.of(Value.ofInt(10), Value.ofInt(20)), null);
        assertEquals(
                TruthValue.TRUE,
                evaluator.evaluateCondition(binary(Expr.Op.LT, outer, inner), row, schema));
    }

    @Test
    void comparesAllSixOperatorsIncludingEqualBoundaries() {
        for (Expr.Op op :
                List.of(Expr.Op.EQ, Expr.Op.NE, Expr.Op.GT, Expr.Op.GE, Expr.Op.LT, Expr.Op.LE)) {
            for (int right : List.of(1, 2, 3)) {
                boolean expected =
                        switch (op) {
                            case EQ -> 2 == right;
                            case NE -> 2 != right;
                            case GT -> 2 > right;
                            case GE -> 2 >= right;
                            case LT -> 2 < right;
                            case LE -> 2 <= right;
                            default -> throw new AssertionError(op);
                        };
                assertEquals(
                        expected ? TruthValue.TRUE : TruthValue.FALSE,
                        truth(binary(op, integer(2), integer(right))),
                        op + " against " + right);
            }
        }
    }

    @Test
    void comparesMixedNumbersWithoutRoundingIntegerToFloat() {
        assertEquals(
                TruthValue.TRUE, truth(binary(Expr.Op.EQ, integer(2), literal(Value.ofFloat(2)))));
        assertEquals(
                TruthValue.TRUE,
                truth(binary(Expr.Op.GT, integer(16_777_217), literal(Value.ofFloat(16_777_216)))));
        assertEquals(
                TruthValue.TRUE,
                truth(binary(Expr.Op.EQ, integer(0), literal(Value.ofFloat(-0.0f)))));
    }

    @Test
    void comparesCharAndVarcharWithCaseSensitiveTextOrdering() {
        Expr a = literal(Value.ofText(DataType.CHAR, "A"));
        Expr b = literal(Value.ofText(DataType.VARCHAR, "a"));
        assertEquals(TruthValue.TRUE, truth(binary(Expr.Op.LT, a, b)));
        assertEquals(
                TruthValue.TRUE,
                truth(binary(Expr.Op.EQ, a, literal(Value.ofText(DataType.VARCHAR, "A")))));
    }

    @Test
    void propagatesTypedNullAndDoesNotUseValueEqualsForSqlNull() {
        Expr nil = literal(Value.nullOf(DataType.INTEGER));
        assertEquals(
                Value.nullOf(DataType.FLOAT),
                scalar(binary(Expr.Op.ADD, nil, literal(Value.ofFloat(2)))));
        for (Expr.Op op :
                List.of(Expr.Op.EQ, Expr.Op.NE, Expr.Op.GT, Expr.Op.GE, Expr.Op.LT, Expr.Op.LE)) {
            assertEquals(TruthValue.UNKNOWN, truth(binary(op, nil, integer(1))));
            assertEquals(TruthValue.UNKNOWN, truth(binary(op, integer(1), nil)));
            assertEquals(TruthValue.UNKNOWN, truth(binary(op, nil, nil)));
        }
        assertFalse(evaluator.test(binary(Expr.Op.EQ, nil, nil), empty, List.of()));
    }

    @Test
    void handlesEveryThreeValuedAndCombination() {
        TruthValue[] values = {TruthValue.TRUE, TruthValue.FALSE, TruthValue.UNKNOWN};
        TruthValue[][] expected = {
            {TruthValue.TRUE, TruthValue.FALSE, TruthValue.UNKNOWN},
            {TruthValue.FALSE, TruthValue.FALSE, TruthValue.FALSE},
            {TruthValue.UNKNOWN, TruthValue.FALSE, TruthValue.UNKNOWN}
        };
        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < values.length; j++) {
                assertEquals(
                        expected[i][j],
                        truth(binary(Expr.Op.AND, predicate(values[i]), predicate(values[j]))));
            }
        }
    }

    @Test
    void shortCircuitsFalseButEvaluatesRightSideOfUnknown() {
        Expr divideByZero =
                binary(Expr.Op.EQ, binary(Expr.Op.DIV, integer(1), integer(0)), integer(1));
        assertEquals(
                TruthValue.FALSE,
                truth(binary(Expr.Op.AND, predicate(TruthValue.FALSE), divideByZero)));
        assertThrows(
                ArithmeticException.class,
                () -> truth(binary(Expr.Op.AND, predicate(TruthValue.UNKNOWN), divideByZero)));
    }

    @Test
    void rejectsZeroDivisorsIncludingFloatingNegativeZero() {
        for (Expr.Op op : List.of(Expr.Op.DIV, Expr.Op.MOD)) {
            assertThrows(
                    ArithmeticException.class, () -> scalar(binary(op, integer(1), integer(0))));
            assertThrows(
                    ArithmeticException.class,
                    () -> scalar(binary(op, integer(1), literal(Value.ofFloat(-0.0f)))));
        }
    }

    @Test
    void rejectsIntegerOverflowAndNonFiniteArithmetic() {
        assertThrows(
                ArithmeticException.class,
                () -> scalar(binary(Expr.Op.ADD, integer(Integer.MAX_VALUE), integer(1))));
        assertThrows(
                ArithmeticException.class,
                () -> scalar(binary(Expr.Op.SUB, integer(Integer.MIN_VALUE), integer(1))));
        assertThrows(
                ArithmeticException.class,
                () -> scalar(binary(Expr.Op.MUL, integer(Integer.MAX_VALUE), integer(2))));
        assertThrows(
                ArithmeticException.class,
                () -> scalar(binary(Expr.Op.DIV, integer(Integer.MIN_VALUE), integer(-1))));
        assertThrows(
                ArithmeticException.class,
                () ->
                        scalar(
                                binary(
                                        Expr.Op.MUL,
                                        literal(Value.ofFloat(Float.MAX_VALUE)),
                                        integer(2))));
    }

    @Test
    void rejectsUnboundOutOfRangeAndMismatchedColumnInput() {
        var row = new ResultRow(List.of(Value.ofInt(7)), null);
        var schema = List.of(new ColumnSchema(new Expr.ColumnRef("t", "c", 0), DataType.INTEGER));
        for (int offset : List.of(-1, 1)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> evaluator.evaluate(new Expr.ColumnRef("t", "c", offset), row, schema));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> evaluator.evaluate(integer(1), row, List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        evaluator.evaluate(
                                new Expr.ColumnRef("t", "c", 0),
                                new ResultRow(List.of(Value.ofFloat(7)), null),
                                schema));
    }

    @Test
    void rejectsInvalidExpressionKindsAndIncompatibleFamilies() {
        Expr text = literal(Value.ofText(DataType.VARCHAR, "1"));
        assertThrows(IllegalArgumentException.class, () -> scalar(new Expr.Null()));
        assertThrows(IllegalArgumentException.class, () -> scalar(predicate(TruthValue.TRUE)));
        assertThrows(IllegalArgumentException.class, () -> truth(integer(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> truth(binary(Expr.Op.ADD, integer(1), integer(2))));
        assertThrows(
                IllegalArgumentException.class,
                () -> scalar(binary(Expr.Op.ADD, text, integer(1))));
        assertThrows(
                IllegalArgumentException.class, () -> truth(binary(Expr.Op.EQ, text, integer(1))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        truth(
                                binary(
                                        Expr.Op.EQ,
                                        literal(Value.nullOf(DataType.VARCHAR)),
                                        integer(1))));
    }

    @Test
    void rejectsJavaNullInputsAndMalformedNodes() {
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(null, empty, List.of()));
        assertThrows(
                NullPointerException.class, () -> evaluator.evaluate(integer(1), null, List.of()));
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(integer(1), empty, null));
        assertThrows(
                NullPointerException.class,
                () -> evaluator.evaluateCondition(null, empty, List.of()));
        assertThrows(NullPointerException.class, () -> scalar(new Expr.Literal(null)));
        assertThrows(
                NullPointerException.class, () -> scalar(binary(Expr.Op.ADD, null, integer(1))));
        assertThrows(NullPointerException.class, () -> truth(binary(null, integer(1), integer(1))));
    }
}
