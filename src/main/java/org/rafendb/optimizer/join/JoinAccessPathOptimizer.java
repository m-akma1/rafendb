package org.rafendb.optimizer.join;

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
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
