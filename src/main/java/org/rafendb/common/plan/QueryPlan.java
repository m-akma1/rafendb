package org.rafendb.common.plan;

import java.util.Objects;

/**
 * An executable query plan produced by the Query Optimizer and consumed by the
 * Query Processor: a tree of {@link PlanNode}s plus the optimizer's estimated total cost.
 *
 * <p>Instances are immutable. Plans may be produced for SELECT, UPDATE, INSERT, DELETE,
 * transaction commands, and CREATE INDEX; concrete node types reflect the statement family.</p>
 *
 * @see PlanNode
 */
public class QueryPlan {
    private final PlanNode root;
    private final double totalCost;

    /**
     * Creates a plan with the given root and estimated total cost.
     *
     * @param root      the root node; must not be {@code null}
     * @param totalCost the estimated total cost, in the optimizer's consistent cost unit (QO-08)
     * @throws NullPointerException if {@code root} is {@code null}
     */
    public QueryPlan(PlanNode root, double totalCost) {
        this.root = Objects.requireNonNull(root, "root");
        this.totalCost = totalCost;
    }

    /**
     * Returns the root node of the plan tree.
     *
     * @return the root node; never {@code null}
     */
    public PlanNode getRoot() {
        return root;
    }

    /**
     * Returns the optimizer's estimated total cost of executing this plan, in the
     * consistent unit documented for QO-08 (an estimate, not a measured cost).
     *
     * @return the estimated total plan cost
     */
    public double getTotalCost() {
        return totalCost;
    }
}