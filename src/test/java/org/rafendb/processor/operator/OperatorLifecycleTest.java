package org.rafendb.processor.operator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.SortPlanNode.Direction;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.operator.filter.FilterOp;
import org.rafendb.processor.operator.join.BnlJoinOp;
import org.rafendb.processor.operator.scan.ProjectOp;
import org.rafendb.processor.operator.sort.SortOp;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * The open/next/close contract every operator with a child must keep. Each case builds the
 * operator over a three-row {@link FakeChildOp}; operator-specific behaviour lives in each
 * operator's own test.
 */
class OperatorLifecycleTest {
    private static final Expr.ColumnRef ID = new Expr.ColumnRef("t", "id", 0);
    private static final List<ColumnSchema> SCHEMA =
            List.of(new ColumnSchema(ID, DataType.INTEGER));

    static Stream<Named<Function<Operator, Operator>>> operators() {
        Expr positive = new Expr.Binary(Expr.Op.GT, ID, new Expr.Literal(Value.ofInt(0)));
        List<ColumnSchema> innerSchema =
                List.of(new ColumnSchema(new Expr.ColumnRef("u", "id", 0), DataType.INTEGER));
        return Stream.of(
                Named.of(
                        "FilterOp",
                        child -> new FilterOp(child, positive, new DefaultExpressionEvaluator())),
                Named.of("ProjectOp", child -> new ProjectOp(child, List.of(ID))),
                Named.of("SortOp", child -> new SortOp(child, 0, Direction.DESC)),
                Named.of(
                        "BnlJoinOp",
                        child ->
                                new BnlJoinOp(
                                        child,
                                        new FakeChildOp(innerSchema, List.of(row(10))),
                                        null)));
    }

    private final FakeChildOp child = new FakeChildOp(SCHEMA, List.of(row(1), row(2), row(3)));

    private static ResultRow row(int id) {
        return new ResultRow(List.of(Value.ofInt(id)), new Rid(1, id));
    }

    private static List<List<Value>> drain(Operator operator) {
        List<List<Value>> rows = new ArrayList<>();
        for (ResultRow row = operator.next(); row != null; row = operator.next()) {
            rows.add(row.values());
        }
        return rows;
    }

    @ParameterizedTest
    @MethodSource("operators")
    void schemaIsKnownBeforeOpenAndAfterClose(Function<Operator, Operator> build) {
        Operator operator = build.apply(child);
        List<ColumnSchema> schema = operator.schema();

        operator.open();
        operator.close();

        assertFalse(schema.isEmpty());
        assertEquals(schema, operator.schema());
    }

    @ParameterizedTest
    @MethodSource("operators")
    void nextOutsideOpenIsRejected(Function<Operator, Operator> build) {
        Operator operator = build.apply(child);
        assertThrows(IllegalStateException.class, operator::next);

        operator.open();
        operator.close();
        assertThrows(IllegalStateException.class, operator::next);
    }

    @ParameterizedTest
    @MethodSource("operators")
    void reopenRestartsAndExhaustionKeepsReturningNull(Function<Operator, Operator> build) {
        Operator operator = build.apply(child);
        operator.open();
        List<List<Value>> all = drain(operator);
        assertNull(operator.next());

        operator.open();
        operator.next();
        operator.open();
        assertEquals(all, drain(operator));

        operator.close();
        operator.open();
        assertEquals(all, drain(operator));
        operator.close();
        assertEquals(3, all.size());
    }

    @ParameterizedTest
    @MethodSource("operators")
    void closeIsSafeAnytimeAndClosesTheChild(Function<Operator, Operator> build) {
        Operator operator = build.apply(child);
        operator.close();

        operator.open();
        operator.close();
        operator.close();

        assertThrows(IllegalStateException.class, child::next, "child must be closed");
    }

    @ParameterizedTest
    @MethodSource("operators")
    void failedChildOpenLeavesTheOperatorClosed(Function<Operator, Operator> build) {
        Operator operator = build.apply(child);
        child.failNextOpen();
        assertThrows(RuntimeException.class, operator::open);
        assertThrows(IllegalStateException.class, operator::next);

        operator.open();
        child.failNextOpen();
        assertThrows(RuntimeException.class, operator::open);
        assertThrows(IllegalStateException.class, operator::next);
        operator.close();
    }
}
