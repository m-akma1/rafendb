package org.rafendb.processor.operator.sort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.SortPlanNode.Direction;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class SortOpTest {
    private static final List<ColumnSchema> SCHEMA =
            List.of(
                    new ColumnSchema(new Expr.ColumnRef("t", "key", 0), DataType.INTEGER),
                    new ColumnSchema(new Expr.ColumnRef("t", "label", 1), DataType.VARCHAR));

    @Test
    void sortsAscendingStablyAndPlacesNullFirst() {
        MemoryOperator child =
                input(
                        row(Value.ofInt(2), text("first two")),
                        row(Value.nullOf(DataType.INTEGER), text("null")),
                        row(Value.ofInt(1), text("one")),
                        row(Value.ofInt(2), text("second two")));
        SortOp sort = new SortOp(child, 0, Direction.ASC);

        assertEquals(SCHEMA, sort.schema());
        sort.open();
        assertEquals(
                List.of(
                        row(Value.nullOf(DataType.INTEGER), text("null")),
                        row(Value.ofInt(1), text("one")),
                        row(Value.ofInt(2), text("first two")),
                        row(Value.ofInt(2), text("second two"))),
                drain(sort));
        sort.close();
    }

    @Test
    void sortsDescendingAndPlacesNullLast() {
        MemoryOperator child =
                input(
                        row(Value.ofInt(2), text("two")),
                        row(Value.nullOf(DataType.INTEGER), text("null")),
                        row(Value.ofInt(1), text("one")));
        SortOp sort = new SortOp(child, 0, Direction.DESC);

        sort.open();
        assertEquals(
                List.of(
                        row(Value.ofInt(2), text("two")),
                        row(Value.ofInt(1), text("one")),
                        row(Value.nullOf(DataType.INTEGER), text("null"))),
                drain(sort));
        sort.close();
    }

    @Test
    void comparesIntegersAndFloatsAsNumbers() {
        MemoryOperator child =
                input(
                        row(Value.ofInt(16_777_217), text("integer")),
                        row(Value.ofFloat(16_777_216.0f), text("float")),
                        row(Value.ofInt(1), text("integer one")),
                        row(Value.ofFloat(1.0f), text("float one")));
        SortOp sort = new SortOp(child, 0, Direction.ASC);

        sort.open();
        assertEquals(
                List.of(
                        row(Value.ofInt(1), text("integer one")),
                        row(Value.ofFloat(1.0f), text("float one")),
                        row(Value.ofFloat(16_777_216.0f), text("float")),
                        row(Value.ofInt(16_777_217), text("integer"))),
                drain(sort));
        sort.close();
    }

    @Test
    void comparesTextByUnicodeCodePointRatherThanUtf16Unit() {
        String bmpPrivateUse = "\uE000";
        String supplementary = "\uD800\uDC00";
        MemoryOperator child =
                input(
                        row(text(supplementary), text("supplementary")),
                        row(text(bmpPrivateUse), text("bmp")));
        SortOp sort = new SortOp(child, 0, Direction.ASC);

        sort.open();
        assertEquals(
                List.of(
                        row(text(bmpPrivateUse), text("bmp")),
                        row(text(supplementary), text("supplementary"))),
                drain(sort));
        sort.close();
    }

    @Test
    void reopenResetsChildAndSortPosition() {
        MemoryOperator child =
                input(row(Value.ofInt(2), text("two")), row(Value.ofInt(1), text("one")));
        SortOp sort = new SortOp(child, 0, Direction.ASC);

        sort.open();
        assertEquals(row(Value.ofInt(1), text("one")), sort.next());
        sort.open();
        assertEquals(
                List.of(row(Value.ofInt(1), text("one")), row(Value.ofInt(2), text("two"))),
                drain(sort));
        assertEquals(2, child.openCount);
        sort.close();
    }

    @Test
    void rejectsInvalidKeyOffsetAndNextBeforeOpen() {
        MemoryOperator child = input(row(Value.ofInt(1), text("one")));

        assertThrows(IllegalArgumentException.class, () -> new SortOp(child, -1, Direction.ASC));
        assertThrows(IllegalArgumentException.class, () -> new SortOp(child, 2, Direction.ASC));
        assertThrows(IllegalStateException.class, () -> new SortOp(child, 0, Direction.ASC).next());
    }

    private static List<ResultRow> drain(Operator operator) {
        List<ResultRow> rows = new ArrayList<>();
        ResultRow row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        return rows;
    }

    private static MemoryOperator input(ResultRow... rows) {
        return new MemoryOperator(SCHEMA, List.of(rows));
    }

    private static ResultRow row(Value key, Value label) {
        return new ResultRow(List.of(key, label), null);
    }

    private static Value text(String value) {
        return Value.ofText(DataType.VARCHAR, value);
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
