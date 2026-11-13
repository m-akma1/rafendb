package org.rafendb.mock.optimizer.cost;

import java.util.Objects;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.optimizer.cost.Cost;
import org.rafendb.optimizer.cost.CostModel;

/** Deterministic placeholder estimates; does not use storage statistics and is thread-safe. */
public final class MockCostModel implements CostModel {
    private final long defaultRows;

    /** Creates a placeholder model with 100 default rows. */
    public MockCostModel() {
        this(100);
    }

    /**
     * Creates a placeholder model with a fixed row estimate.
     *
     * @param defaultRows nonnegative row count
     * @throws IllegalArgumentException if {@code defaultRows} is negative
     */
    public MockCostModel(long defaultRows) {
        if (defaultRows < 0) throw new IllegalArgumentException("defaultRows must be nonnegative");
        this.defaultRows = defaultRows;
    }

    /** {@inheritDoc} */
    @Override
    public long estimateRows(PlanNode node) {
        requireNode(node);
        if (node instanceof JoinPlanNode) return defaultRows;
        if (node instanceof SelectionPlanNode) return defaultRows / 2;
        if (node instanceof ProjectionPlanNode) return defaultRows;
        return defaultRows;
    }

    /** {@inheritDoc} */
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
        if (Objects.isNull(node)) throw new IllegalArgumentException("Plan node must not be null");
    }
}
