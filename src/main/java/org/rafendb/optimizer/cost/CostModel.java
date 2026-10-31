package org.rafendb.optimizer.cost;

import java.util.Set;
import org.rafendb.common.plan.PlanNode;

/** Estimates output cardinality and local or subtree execution cost. */
public interface CostModel {
    /** Estimates the number of rows output by {@code node}. */
    long estimateRows(PlanNode node);

    /** Estimates only the work performed by {@code node}, excluding its children. */
    Cost estimateLocalCost(PlanNode node);

    /** Sums local cost over the node's subtree, counting each node identity once. */
    Cost estimateTotalCost(PlanNode node);

    Cost total(PlanNode node, Set<PlanNode> visited);
}
