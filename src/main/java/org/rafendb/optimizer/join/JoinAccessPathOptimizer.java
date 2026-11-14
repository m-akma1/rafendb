package org.rafendb.optimizer.join;

import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.optimizer.cost.CostModel;

public final class JoinAccessPathOptimizer {
    private final JoinOptimizer joins = new JoinOptimizer();

    public JoinAccessPathOptimizer() {}

    public JoinAccessPathOptimizer(StorageManager storage) {
        Objects.requireNonNull(storage, "storage");
    }

    public JoinAccessPathOptimizer(StorageManager storage, CostModel costs) {
        this(storage);
        Objects.requireNonNull(costs, "costs");
    }

    /**
     * Normalizes a query plan without recalculating its cost.
     *
     * @param plan the validated query plan
     * @return the normalized plan, or the original instance if unchanged
     */
    public QueryPlan optimize(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        PlanNode normalized = joins.normalize(plan.root());
        return normalized == plan.root() ? plan : new QueryPlan(normalized, plan.totalCost());
    }
}
