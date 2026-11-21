package org.rafendb.processor.operator.filter;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/** Lifecycle is covered by OperatorLifecycleTest. */
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

    private static ResultRow row(int nim, Value ipk) {
        return new ResultRow(List.of(Value.ofInt(nim), ipk), new Rid(1, nim));
    }

    @Test
    void passesOnlyRowsWhereTheConditionIsTrueAndKeepsThemIntact() {
        ResultRow high = row(1, Value.ofFloat(3.5f));
        ResultRow low = row(2, Value.ofFloat(2.0f));
        ResultRow unknown = row(3, Value.nullOf(DataType.FLOAT));
        ResultRow alsoHigh = row(4, Value.ofFloat(3.9f));
        FakeChildOp child = new FakeChildOp(SCHEMA, List.of(high, low, unknown, alsoHigh));
        FilterOp filter = new FilterOp(child, CONDITION, new DefaultExpressionEvaluator());

        filter.open();
        assertSame(high, filter.next());
        assertSame(alsoHigh, filter.next());
        assertNull(filter.next());
        filter.close();
    }
}
