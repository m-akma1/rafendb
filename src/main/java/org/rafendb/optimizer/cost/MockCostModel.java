package org.rafendb.optimizer.cost;

import java.util.Objects;
import org.rafendb.common.error.CostEstimationException;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;

/** Deterministic placeholder estimates; does not use storage statistics. */
public final class MockCostModel implements CostModel {
    private final long defaultRows;

    public MockCostModel() {
        this(100);
    }

    public MockCostModel(long defaultRows) {
        if (defaultRows < 0) throw new IllegalArgumentException("defaultRows must be nonnegative");
        this.defaultRows = defaultRows;
    }

    @Override
    public long estimateRows(PlanNode node) {
        requireNode(node);
        if (node instanceof JoinPlanNode) return defaultRows;
        if (node instanceof SelectionPlanNode) return defaultRows / 2;
        if (node instanceof ProjectionPlanNode) return defaultRows;
        return defaultRows;
    }

    @Override
    public Cost estimateLocalCost(PlanNode node) {
        requireNode(node);
        // TODO: use TableStats, IndexInfo, and QO4/QO5 plan metadata when available.
        if (node instanceof JoinPlanNode) return new Cost(2, defaultRows);
        if (node instanceof SelectionPlanNode) return new Cost(1, defaultRows / 2);
        if (node instanceof ProjectionPlanNode) return new Cost(0, defaultRows / 10);
        return new Cost(1, defaultRows);
    }

    private static void requireNode(PlanNode node) {
        if (Objects.isNull(node)) throw new CostEstimationException("Plan node must not be null");
    }
}
