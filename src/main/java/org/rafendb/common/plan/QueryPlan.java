package org.rafendb.common.plan;

import java.util.Objects;

public class QueryPlan {
    private final PlanNode root;
    private final double totalCost;

    public QueryPlan(PlanNode root, double totalCost) {
        this.root = Objects.requireNonNull(root, "root");
        this.totalCost = totalCost;
    }

    public PlanNode getRoot() {
        return root;
    }

    public double getTotalCost() {
        return totalCost;
    }
}
