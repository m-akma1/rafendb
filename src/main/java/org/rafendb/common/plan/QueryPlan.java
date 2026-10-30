package org.rafendb.common.plan;

import java.util.Objects;

/** QO plan wrapper. Constructor and accessor naming are provisional until QO confirms them. */
public class QueryPlan {
    private final PlanNode root;
    private final double totalCost;

    /** Stores a non-null tree and the optimizer cost unchanged; does not execute the tree. */
    public QueryPlan(PlanNode root, double totalCost) {
        this.root = Objects.requireNonNull(root, "root");
        this.totalCost = totalCost;
    }

    /** Returns the root; local accessor name, not yet confirmed by QO. */
    public PlanNode getRoot() {
        return root;
    }

    /** Returns the supplied cost; local accessor name, not yet confirmed by QO. */
    public double getTotalCost() {
        return totalCost;
    }
}
