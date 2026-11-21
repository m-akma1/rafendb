package org.rafendb.processor.operator.join;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.expr.TruthValue;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class BnlJoinOpTest {
    private static final Expr CONDITION =
            new Expr.Binary(
                    Expr.Op.EQ,
                    new Expr.ColumnRef("outer", "id", 0),
                    new Expr.ColumnRef("inner", "id", 1));

    @Test
    void producesCartesianProductAndConcatenatedSchema() {
        FakeChildOp outer = input(List.of(column("outer", "id", 0)), row(1), row(2));
        FakeChildOp inner = input(List.of(column("inner", "id", 0)), row(10), row(20));
        BnlJoinOp join = new BnlJoinOp(outer, inner, null);

        assertEquals(List.of(column("outer", "id", 0), column("inner", "id", 0)), join.schema());

        join.open();
        assertEquals(List.of(row(1, 10), row(2, 10), row(1, 20), row(2, 20)), drain(join));
        assertEquals(1, inner.getOpenCount());
        join.close();
    }

    @Test
    void appliesInjectedEvaluatorToJoinedRows() {
        FakeChildOp outer = input(List.of(column("outer", "id", 0)), row(1), row(2));
        FakeChildOp inner = input(List.of(column("inner", "id", 0)), row(2), row(3));
        BnlJoinOp join =
                new BnlJoinOp(outer, inner, CONDITION, new DefaultExpressionEvaluator(), 1024);

        join.open();
        assertEquals(List.of(row(2, 2)), drain(join));
        join.close();
    }

    @Test
    void rejectsUnknownJoinCondition() {
        FakeChildOp outer = input(List.of(column("outer", "id", 0)), row(1));
        FakeChildOp inner = input(List.of(column("inner", "id", 0)), row(1));
        BnlJoinOp join = new BnlJoinOp(outer, inner, CONDITION, new UnknownEvaluator(), 1024);

        join.open();
        assertEquals(List.of(), drain(join));
        join.close();
    }

    @Test
    void rescansInnerForEachOuterBlockAndAllowsOversizedRows() {
        FakeChildOp outer = input(List.of(column("outer", "id", 0)), row(1), row(2), row(3));
        FakeChildOp inner = input(List.of(column("inner", "id", 0)), row(9));
        BnlJoinOp join = new BnlJoinOp(outer, inner, null, 1);

        join.open();
        assertEquals(List.of(row(1, 9), row(2, 9), row(3, 9)), drain(join));
        assertEquals(3, inner.getOpenCount());
        join.close();
    }

    @Test
    void returnsNoRowsWhenEitherInputIsEmpty() {
        FakeChildOp emptyOuter = input(List.of(column("outer", "id", 0)));
        FakeChildOp nonEmptyInner = input(List.of(column("inner", "id", 0)), row(1));
        BnlJoinOp emptyOuterJoin = new BnlJoinOp(emptyOuter, nonEmptyInner, null);

        emptyOuterJoin.open();
        assertEquals(List.of(), drain(emptyOuterJoin));
        emptyOuterJoin.close();

        FakeChildOp nonEmptyOuter = input(List.of(column("outer", "id", 0)), row(1));
        FakeChildOp emptyInner = input(List.of(column("inner", "id", 0)));
        BnlJoinOp emptyInnerJoin = new BnlJoinOp(nonEmptyOuter, emptyInner, null);

        emptyInnerJoin.open();
        assertEquals(List.of(), drain(emptyInnerJoin));
        emptyInnerJoin.close();
    }

    @Test
    void rejectsANonPositiveBlockBudget() {
        FakeChildOp outer = input(List.of(column("outer", "id", 0)), row(1));
        FakeChildOp inner = input(List.of(column("inner", "id", 0)), row(2));

        assertThrows(IllegalArgumentException.class, () -> new BnlJoinOp(outer, inner, null, 0));
    }

    private static List<ResultRow> drain(Operator operator) {
        List<ResultRow> rows = new ArrayList<>();
        ResultRow row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        return rows;
    }

    private static FakeChildOp input(List<ColumnSchema> schema, ResultRow... rows) {
        return new FakeChildOp(schema, List.of(rows));
    }

    private static ColumnSchema column(String table, String name, int offset) {
        return new ColumnSchema(new Expr.ColumnRef(table, name, offset), DataType.INTEGER);
    }

    private static ResultRow row(int... values) {
        return new ResultRow(java.util.Arrays.stream(values).mapToObj(Value::ofInt).toList(), null);
    }

    private static final class UnknownEvaluator implements ExpressionEvaluator {
        @Override
        public Value evaluate(Expr expression, ResultRow row, List<ColumnSchema> schema) {
            throw new UnsupportedOperationException("Scalar evaluation is not needed in this test");
        }

        @Override
        public TruthValue evaluateCondition(
                Expr condition, ResultRow row, List<ColumnSchema> schema) {
            return TruthValue.UNKNOWN;
        }
    }
}
