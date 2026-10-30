package org.rafendb.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rafendb.api.processor.ExecutionPlan;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.expr.Expression;
import org.rafendb.common.expr.Predicate;
import org.rafendb.common.plan.*;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;
import org.rafendb.mock.processor.MockExpressionEvaluator;
import org.rafendb.mock.processor.MockPlanTranslator;

class Qp2ContractTest {
    @Test
    void wrapperExposesTraversableTreeWithoutEmbeddingChildrenInDescriptions() {
        var left = new TableScanNode("pegawai");
        var right = new TableScanNode("departemen");
        var root = new CartesianProductPlanNode(left, right);
        var plan = new QueryPlan(root, 27.5);
        assertSame(root, plan.getRoot());
        assertEquals(27.5, plan.getTotalCost());
        assertEquals(List.of(left, right), plan.getRoot().children());
        assertTrue(left.children().isEmpty());
        assertFalse(root.describe().contains("\n"));
        assertFalse(root.describe().contains("pegawai"));
        assertThrows(UnsupportedOperationException.class, () -> root.children().clear());
        assertThrows(NullPointerException.class, () -> new QueryPlan(null, 0));
    }

    private final ColumnRef salary = new ColumnRef("pegawai", "salary");
    private final Expression value = new Expression.Literal(new Value.IntegerValue(5000));
    private final Predicate condition =
            new Predicate.Comparison(
                    new Expression.Column(salary), Predicate.ComparisonOp.GT, value);

    @Test
    void qoFixtureCanCrossBoundaryAndPreserveEmptyHeaders() {
        var plan =
                new ProjectionPlanNode(
                        new SelectionPlanNode(new TableScanNode("pegawai"), condition),
                        List.of(salary));
        var translator = new MockPlanTranslator(List.of(salary));
        var result =
                assertInstanceOf(
                        ExecutionPlan.Rows.class, translator.translate(new QueryPlan(plan, 12.5)));
        assertEquals(List.of(salary), result.columns());
        var cursor = result.operator();
        assertThrows(IllegalStateException.class, cursor::next);
        cursor.open();
        assertNull(cursor.next());
        assertNull(cursor.next());
        cursor.open();
        assertNull(cursor.next());
        assertEquals(List.of(salary), cursor.schema());
        cursor.close();
        cursor.close();
        assertThrows(IllegalStateException.class, cursor::next);
        var other =
                assertInstanceOf(
                        ExecutionPlan.Rows.class, translator.translate(new QueryPlan(plan, 12.5)));
        assertNotSame(cursor, other.operator());
        other.operator().open();
        assertNull(other.operator().next());
        other.operator().close();
    }

    @Test
    void everyCommandReturnsExplicitDummyOutcomeOnce() {
        var plans =
                List.<PlanNode>of(
                        new InsertPlanNode("pegawai", List.of(new Value.IntegerValue(1))),
                        new UpdatePlanNode("pegawai", salary, value, Optional.of(condition)),
                        new DeletePlanNode("pegawai", Optional.empty()),
                        new CreateIndexPlanNode(
                                "salary_idx",
                                "pegawai",
                                salary,
                                CreateIndexPlanNode.IndexKind.BTREE),
                        new TransactionPlanNode(TransactionPlanNode.Action.BEGIN));
        var translator = new MockPlanTranslator(List.of());
        for (var plan : plans) {
            var result =
                    assertInstanceOf(
                            ExecutionPlan.Command.class,
                            translator.translate(new QueryPlan(plan, 12.5)));
            var outcome = result.action().execute();
            assertEquals(0, outcome.affectedRows());
            assertTrue(outcome.status().startsWith("MOCK:"));
            assertThrows(IllegalStateException.class, result.action()::execute);
        }
    }

    @Test
    void fixtureOwnershipAndRowShapeAreProtected() {
        var columns = new ArrayList<>(List.of(salary));
        var row = new Row(columns, List.of(new Value.IntegerValue(6000)));
        columns.clear();
        assertEquals(List.of(salary), row.columns());
        assertThrows(UnsupportedOperationException.class, () -> row.columns().clear());
        assertThrows(IllegalArgumentException.class, () -> new Row(List.of(salary), List.of()));
        assertThrows(
                NullPointerException.class,
                () -> new SelectionPlanNode(new TableScanNode("pegawai"), null));
        assertThrows(
                NullPointerException.class,
                () -> new MockPlanTranslator(List.of()).translate(null));
    }

    @Test
    void evaluatorIsClearlyConfiguredDummyRatherThanSqlEvaluation() {
        var dummy = new Value.IntegerValue(42);
        var evaluator = new MockExpressionEvaluator(dummy, false);
        var row = new Row(List.of(salary), List.of(new Value.IntegerValue(6000)));
        assertEquals(dummy, evaluator.evaluate(value, row));
        assertFalse(evaluator.test(condition, row));
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(null, row));
        assertThrows(NullPointerException.class, () -> evaluator.test(condition, null));
    }
}
