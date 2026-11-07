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
        MemoryOperator outer = input(List.of(column("outer", "id", 0)), row(1), row(2));
        MemoryOperator inner = input(List.of(column("inner", "id", 0)), row(10), row(20));
        BnlJoinOp join = new BnlJoinOp(outer, inner, null);

        assertEquals(List.of(column("outer", "id", 0), column("inner", "id", 0)), join.schema());

        join.open();
        assertEquals(List.of(row(1, 10), row(2, 10), row(1, 20), row(2, 20)), drain(join));
        assertEquals(1, inner.openCount);
        join.close();
    }

    @Test
    void appliesInjectedEvaluatorToJoinedRows() {
        MemoryOperator outer = input(List.of(column("outer", "id", 0)), row(1), row(2));
        MemoryOperator inner = input(List.of(column("inner", "id", 0)), row(2), row(3));
        BnlJoinOp join =
                new BnlJoinOp(outer, inner, CONDITION, new DefaultExpressionEvaluator(), 1024);

        join.open();
        assertEquals(List.of(row(2, 2)), drain(join));
        join.close();
    }

    @Test
    void rejectsUnknownJoinCondition() {
        MemoryOperator outer = input(List.of(column("outer", "id", 0)), row(1));
        MemoryOperator inner = input(List.of(column("inner", "id", 0)), row(1));
        BnlJoinOp join = new BnlJoinOp(outer, inner, CONDITION, new UnknownEvaluator(), 1024);

        join.open();
        assertEquals(List.of(), drain(join));
        join.close();
    }

    @Test
    void rescansInnerForEachOuterBlockAndAllowsOversizedRows() {
        MemoryOperator outer = input(List.of(column("outer", "id", 0)), row(1), row(2), row(3));
        MemoryOperator inner = input(List.of(column("inner", "id", 0)), row(9));
        BnlJoinOp join = new BnlJoinOp(outer, inner, null, 1);

        join.open();
        assertEquals(List.of(row(1, 9), row(2, 9), row(3, 9)), drain(join));
        assertEquals(3, inner.openCount);
        join.close();
    }

    @Test
    void returnsNoRowsWhenEitherInputIsEmpty() {
        MemoryOperator emptyOuter = input(List.of(column("outer", "id", 0)));
        MemoryOperator nonEmptyInner = input(List.of(column("inner", "id", 0)), row(1));
        BnlJoinOp emptyOuterJoin = new BnlJoinOp(emptyOuter, nonEmptyInner, null);

        emptyOuterJoin.open();
        assertEquals(List.of(), drain(emptyOuterJoin));
        emptyOuterJoin.close();

        MemoryOperator nonEmptyOuter = input(List.of(column("outer", "id", 0)), row(1));
        MemoryOperator emptyInner = input(List.of(column("inner", "id", 0)));
        BnlJoinOp emptyInnerJoin = new BnlJoinOp(nonEmptyOuter, emptyInner, null);

        emptyInnerJoin.open();
        assertEquals(List.of(), drain(emptyInnerJoin));
        emptyInnerJoin.close();
    }

    @Test
    void rejectsInvalidBudgetAndNextBeforeOpen() {
        MemoryOperator outer = input(List.of(column("outer", "id", 0)), row(1));
        MemoryOperator inner = input(List.of(column("inner", "id", 0)), row(2));

        assertThrows(IllegalArgumentException.class, () -> new BnlJoinOp(outer, inner, null, 0));
        assertThrows(IllegalStateException.class, () -> new BnlJoinOp(outer, inner, null).next());
    }

    private static List<ResultRow> drain(Operator operator) {
        List<ResultRow> rows = new ArrayList<>();
        ResultRow row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        return rows;
    }

    private static MemoryOperator input(List<ColumnSchema> schema, ResultRow... rows) {
        return new MemoryOperator(schema, List.of(rows));
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

    private static final class MemoryOperator implements Operator {
        private final List<ColumnSchema> schema;
        private final List<ResultRow> rows;
        private int position;
        private int openCount;
        private boolean open;

        private MemoryOperator(List<ColumnSchema> schema, List<ResultRow> rows) {
            this.schema = List.copyOf(schema);
            this.rows = List.copyOf(rows);
        }

        @Override
        public void open() {
            position = 0;
            open = true;
            openCount++;
        }

        @Override
        public List<ColumnSchema> schema() {
            return schema;
        }

        @Override
        public ResultRow next() {
            if (!open) {
                throw new IllegalStateException("Input operator is not open");
            }
            return position < rows.size() ? rows.get(position++) : null;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
