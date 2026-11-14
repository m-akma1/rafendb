package org.rafendb.optimizer.cost;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.plan.PlanNode;

/** Estimates output cardinality and local or subtree execution cost. */
public interface CostModel {
    /** Estimates the number of rows output by {@code node}. */
    long estimateRows(PlanNode node) throws CostEstimationException;

    /** Estimates only the work performed by {@code node}, excluding its children. */
    Cost estimateLocalCost(PlanNode node) throws CostEstimationException;

    /** Sums local cost over the node's subtree, counting each node identity once. */
    default Cost estimateTotalCost(PlanNode node) throws CostEstimationException {
        Objects.requireNonNull(node, "plan node");
        Set<PlanNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return totalCost(this, node, seen);
    }

    private static Cost totalCost(CostModel model, PlanNode node, Set<PlanNode> seen)
            throws CostEstimationException {
        if (!seen.add(node)) return new Cost(0, 0);
        Cost total = Objects.requireNonNull(model.estimateLocalCost(node), "local cost");
        var children = Objects.requireNonNull(node.children(), "plan node children");
        for (PlanNode child : children) {
            total =
                    total.plus(
                            totalCost(
                                    model, Objects.requireNonNull(child, "plan node child"), seen));
        }
        return total;
    }
}
