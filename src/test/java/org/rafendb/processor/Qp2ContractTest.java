package org.rafendb.processor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.rafendb.api.processor.ExecutionPlan;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.ResultRow;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
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
    private final ColumnSchema salarySchema =
            new ColumnSchema(new Expr.ColumnRef("pegawai", "salary"), DataType.INTEGER);
    private final Expr value = new Expr.Literal(Value.ofInt(5000));
    private final Expr condition =
            new Expr.Binary(Expr.Op.GT, new Expr.ColumnRef("pegawai", "salary"), value);

    @Test
    void qoFixtureCanCrossBoundaryAndPreserveEmptyHeaders() {
        var plan =
                new ProjectionPlanNode(
                        new SelectionPlanNode(new TableScanNode("pegawai"), condition),
                        List.of(salary));
        var translator = new MockPlanTranslator(List.of(salarySchema));
        var result =
                assertInstanceOf(
                        ExecutionPlan.Rows.class, translator.translate(new QueryPlan(plan, 12.5)));
        assertEquals(List.of(salarySchema), result.columns());
        var cursor = result.operator();
        assertThrows(IllegalStateException.class, cursor::next);
        cursor.open();
        assertNull(cursor.next());
        assertNull(cursor.next());
        cursor.open();
        assertNull(cursor.next());
        assertEquals(List.of(salarySchema), cursor.schema());
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
                        new InsertPlanNode("pegawai", List.of(Value.ofInt(1))),
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
        var values = new ArrayList<Value>(List.of(Value.ofInt(6000)));
        var rid = new Rid(1, 2);
        var row = new ResultRow(values, rid);
        values.clear();
        assertEquals(List.of(Value.ofInt(6000)), row.values());
        assertEquals(rid, row.rid());
        var storageRow = new Row(row.values());
        assertEquals(row.values(), storageRow.values());
        assertEquals(row.get(0), storageRow.get(0));
        assertEquals(row.size(), storageRow.size());
        assertThrows(UnsupportedOperationException.class, () -> storageRow.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> row.values().clear());
        assertThrows(NullPointerException.class, () -> new ResultRow(null, null));
        assertThrows(
                NullPointerException.class,
                () -> new SelectionPlanNode(new TableScanNode("pegawai"), null));
        assertThrows(
                NullPointerException.class,
                () -> new MockPlanTranslator(List.of()).translate(null));
    }

    @Test
    void evaluatorIsClearlyConfiguredDummyRatherThanSqlEvaluation() {
        var dummy = Value.ofInt(42);
        var evaluator = new MockExpressionEvaluator(dummy, false);
        var row = new ResultRow(List.of(), null);
        List<ColumnSchema> schema = List.of();
        Expr literal = new Expr.Literal(Value.ofInt(5000));
        Expr comparison = new Expr.Binary(Expr.Op.GT, literal, new Expr.Literal(Value.ofInt(0)));
        assertEquals(dummy, evaluator.evaluate(literal, row, schema));
        assertFalse(evaluator.test(comparison, row, schema));
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(null, row, schema));
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(literal, null, schema));
        assertThrows(NullPointerException.class, () -> evaluator.evaluate(literal, row, null));
        assertThrows(NullPointerException.class, () -> evaluator.test(null, row, schema));
        assertThrows(NullPointerException.class, () -> evaluator.test(comparison, null, schema));
        assertThrows(NullPointerException.class, () -> evaluator.test(comparison, row, null));
    }
}
