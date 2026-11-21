package org.rafendb.processor.subquery;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.FakeChildOp;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.plan.PlanTranslator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

class PlanSubqueryRunnerTest {
    // SELECT n.nim FROM nilai n
    private static final SelectQuery QUERY =
            new SelectQuery(
                    "SELECT n.nim FROM nilai n",
                    List.of(new Expr.ColumnRef("n", "nim", 0)),
                    new FromItem.TableRef("nilai", "n"),
                    null,
                    List.of());
    private static final QueryPlan PLAN = new QueryPlan(new TableScanNode("n", "nilai"), 1.0);

    // Optimizer palsu: nyatet query yang masuk, balikin PLAN atau lempar failure kalau diset.
    private static final class FakeOptimizer implements QueryOptimizer {
        private ParsedQuery received;
        private OptimizerInternalException failure;

        @Override
        public ParsedQuery parse(String sql) {
            throw new UnsupportedOperationException();
        }

        @Override
        public QueryPlan optimize(ParsedQuery bound) throws OptimizerInternalException {
            received = bound;
            if (failure != null) {
                throw failure;
            }
            return PLAN;
        }

        @Override
        public String explain(QueryPlan plan) {
            throw new UnsupportedOperationException();
        }
    }

    // Translator palsu: nyatet plan yang masuk, balikin hasil yang tetap.
    private static final class FakeTranslator implements PlanTranslator {
        private final ExecutionPlan result;
        private QueryPlan received;

        private FakeTranslator(ExecutionPlan result) {
            this.result = result;
        }

        @Override
        public ExecutionPlan translate(QueryPlan plan) {
            received = plan;
            return result;
        }
    }

    @Test
    void optimizesThenTranslatesAndReturnsTheRows() {
        List<ColumnSchema> schema =
                List.of(new ColumnSchema(new Expr.ColumnRef("n", "nim"), DataType.INTEGER));
        List<ResultRow> values =
                List.of(
                        new ResultRow(List.of(Value.ofInt(1)), null),
                        new ResultRow(List.of(Value.ofInt(2)), null));
        FakeChildOp operator = new FakeChildOp(schema, values);
        ExecutionPlan.Rows rows = new ExecutionPlan.Rows(schema, operator);
        FakeOptimizer optimizer = new FakeOptimizer();
        FakeTranslator translator = new FakeTranslator(rows);
        PlanSubqueryRunner runner = new PlanSubqueryRunner(optimizer, translator);

        assertSame(rows, runner.prepare(QUERY));
        assertSame(QUERY, optimizer.received);
        assertSame(PLAN, translator.received);
        assertEquals(0, operator.getOpenCount());

        // SELECT n.nim FROM nilai n LIMIT 1
        SelectQuery limited =
                new SelectQuery(
                        QUERY.originalSql(), QUERY.projection(), QUERY.from(), null, List.of(), 1);
        Operator limitedOperator = runner.prepare(limited).operator();
        limitedOperator.open();
        assertNotNull(limitedOperator.next());
        assertNull(limitedOperator.next());
        limitedOperator.close();
    }

    @Test
    void prepareRejectsBadInput() {
        ExecutionPlan.Command command =
                new ExecutionPlan.Command(() -> new ExecutionPlan.CommandResult(0, "OK"));
        PlanSubqueryRunner commandRunner =
                new PlanSubqueryRunner(new FakeOptimizer(), new FakeTranslator(command));
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> commandRunner.prepare(QUERY));
        assertEquals("Subquery plan must return rows", error.getMessage());

        FakeOptimizer broken = new FakeOptimizer();
        broken.failure = new OptimizerInternalException("optimizer broke");
        FakeTranslator unused = new FakeTranslator(command);
        PlanSubqueryRunner brokenRunner = new PlanSubqueryRunner(broken, unused);
        error = assertThrows(IllegalStateException.class, () -> brokenRunner.prepare(QUERY));
        assertEquals("Subquery optimization failed", error.getMessage());
        assertSame(broken.failure, error.getCause());
        assertNull(unused.received);
    }

    @Test
    void constructorRejectsNull() {
        FakeOptimizer optimizer = new FakeOptimizer();
        FakeTranslator translator = new FakeTranslator(null);
        assertThrows(NullPointerException.class, () -> new PlanSubqueryRunner(null, translator));
        assertThrows(NullPointerException.class, () -> new PlanSubqueryRunner(optimizer, null));
    }
}
