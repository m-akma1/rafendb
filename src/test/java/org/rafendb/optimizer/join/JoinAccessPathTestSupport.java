package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.plan.*;
import org.rafendb.common.type.Value;
import org.rafendb.mock.concurrency.MockConcurrencyControl;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.optimizer.cost.Cost;
import org.rafendb.optimizer.cost.CostModel;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.operator.index.IndexScanOp;
import org.rafendb.processor.operator.scan.TableScanOp;
import org.rafendb.processor.plan.DefaultOperatorFactory;
import org.rafendb.processor.plan.DefaultPlanTranslator;
import org.rafendb.processor.tuple.ResultRow;

final class JoinAccessPathTestSupport {
    private JoinAccessPathTestSupport() {}

    static final TableScanNode STUDENTS = new TableScanNode("m", "mahasiswa");
    static final TableScanNode PROGRAMS = new TableScanNode("p", "prodi");

    /** Counts QP result rows by their ordered values so duplicate rows remain visible. */
    static Map<List<Value>, Integer> rows(MockStorageManager storage, PlanNode root) {
        var session = new SessionContext(1);
        var concurrency = new MockConcurrencyControl();
        session.setCurrentTxId(concurrency.begin());
        var context = new ExecutionContext(session, concurrency, storage, null);
        var factory =
                new DefaultOperatorFactory(
                        DefaultOperatorFactory.bindScan(context, TableScanOp::new),
                        DefaultOperatorFactory.bindIndexScan(context, IndexScanOp::new),
                        insert -> fail("Unexpected INSERT"),
                        (update, child) -> fail("Unexpected UPDATE"),
                        (delete, child) -> fail("Unexpected DELETE"),
                        create -> fail("Unexpected CREATE INDEX"));
        var translated = new DefaultPlanTranslator(factory).translate(new QueryPlan(root, 0));
        var execution = assertInstanceOf(ExecutionPlan.Rows.class, translated);

        var counts = new HashMap<List<Value>, Integer>();
        try (Operator operator = execution.operator()) {
            operator.open();
            for (ResultRow row = operator.next(); row != null; row = operator.next()) {
                counts.merge(row.values(), 1, Integer::sum);
            }
        }
        return counts;
    }

    static <T extends PlanNode> List<T> nodes(PlanNode root, Class<T> type) {
        List<T> found = new ArrayList<>();
        if (type.isInstance(root)) found.add(type.cast(root));
        for (PlanNode child : root.children()) found.addAll(nodes(child, type));
        return found;
    }

    static List<String> scanOrder(PlanNode root) {
        List<String> order = new ArrayList<>();
        for (PlanNode node : nodes(root, PlanNode.class)) {
            if (node instanceof TableScanNode scan)
                order.add(scan.alias() == null ? scan.table() : scan.alias());
            if (node instanceof IndexScanNode scan)
                order.add(scan.alias() == null ? scan.table() : scan.alias());
        }
        return order;
    }

    /** Only the complete-plan estimate is requested at the join/access search boundary. */
    @FunctionalInterface
    interface PlanCost extends CostModel {
        @Override
        Cost estimateTotalCost(PlanNode root) throws CostEstimationException;

        @Override
        default long estimateRows(PlanNode node) {
            throw new UnsupportedOperationException("Expected a complete-plan cost request");
        }

        @Override
        default Cost estimateLocalCost(PlanNode node) {
            throw new UnsupportedOperationException("Expected a complete-plan cost request");
        }
    }

    static void assertCheapest(QueryPlan chosen, Map<PlanNode, Double> evaluated) {
        assertEquals(
                evaluated.get(chosen.root()),
                chosen.totalCost(),
                "Cost recorded for the selected plan");
        assertEquals(
                Collections.min(evaluated.values()),
                chosen.totalCost(),
                "Lowest cost among evaluated plans");
    }
}
