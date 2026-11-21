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
import org.rafendb.processor.operator.FakeChildOp;
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
        FakeChildOp child =
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
        FakeChildOp child =
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
        FakeChildOp child =
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
        FakeChildOp child =
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
    void rejectsKeyOffsetOutsideTheChildSchema() {
        FakeChildOp child = input(row(Value.ofInt(1), text("one")));

        assertThrows(IllegalArgumentException.class, () -> new SortOp(child, -1, Direction.ASC));
        assertThrows(IllegalArgumentException.class, () -> new SortOp(child, 2, Direction.ASC));
    }

    private static List<ResultRow> drain(Operator operator) {
        List<ResultRow> rows = new ArrayList<>();
        ResultRow row;
        while ((row = operator.next()) != null) {
            rows.add(row);
        }
        return rows;
    }

    private static FakeChildOp input(ResultRow... rows) {
        return new FakeChildOp(SCHEMA, List.of(rows));
    }

    private static ResultRow row(Value key, Value label) {
        return new ResultRow(List.of(key, label), null);
    }

    private static Value text(String value) {
        return Value.ofText(DataType.VARCHAR, value);
    }
}
