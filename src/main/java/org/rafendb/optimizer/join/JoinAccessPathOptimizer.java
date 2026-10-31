package org.rafendb.optimizer.join;

import java.util.Objects;
import org.rafendb.common.plan.QueryPlan;

/**
 * Coordinates join and access path optimization.
 *
 * <p>The four internal stages are pass-through placeholders. Instances are stateless
 * and thread-safe.
 */
public final class JoinAccessPathOptimizer {
    /** Creates a join and access path optimizer. */
    public JoinAccessPathOptimizer() {}

    /**
     * Runs the four stages in order, currently preserving the plan, its root, and its cost.
     *
     * @param plan the input query plan; must not be {@code null}
     * @return the same plan instance, including its original root and total cost
     * @throws NullPointerException if {@code plan} is {@code null}
     */
    public QueryPlan optimize(QueryPlan plan) {
        QueryPlan currentPlan = Objects.requireNonNull(plan, "plan");
        currentPlan = convertCartesianProductsToJoins(currentPlan);
        currentPlan = reorderJoins(currentPlan);
        currentPlan = selectIndexScans(currentPlan);
        return selectCheapestPlan(currentPlan);
    }

    /** QO-11: Cartesian-to-join transformation placeholder; returns the input unchanged. */
    private QueryPlan convertCartesianProductsToJoins(QueryPlan plan) {
        return plan;
    }

    /** QO-12: Join reordering and regrouping placeholder; returns the input unchanged. */
    private QueryPlan reorderJoins(QueryPlan plan) {
        return plan;
    }

    /** QO-13: IndexScan selection placeholder; returns the input unchanged. */
    private QueryPlan selectIndexScans(QueryPlan plan) {
        return plan;
    }

    /** QO-14: Cheapest-plan selection placeholder; returns the input without comparing costs. */
    private QueryPlan selectCheapestPlan(QueryPlan plan) {
        return plan;
    }
}
