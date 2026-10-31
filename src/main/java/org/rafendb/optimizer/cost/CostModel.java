package org.rafendb.optimizer.cost;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.rafendb.common.error.CostEstimationException;
import org.rafendb.common.plan.PlanNode;

/** Estimates output cardinality and local or subtree execution cost. */
public interface CostModel {
    /** Estimates the number of rows output by {@code node}. */
    long estimateRows(PlanNode node);

    /** Estimates only the work performed by {@code node}, excluding its children. */
    Cost estimateLocalCost(PlanNode node);

    /** Sums local cost over the node's subtree, counting each node identity once. */
    default Cost estimateTotalCost(PlanNode node) {
        if (node == null) {
            throw new CostEstimationException("Cannot estimate cost for a null plan node");
        }
        Set<PlanNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return total(node, visited);
    }

    private Cost total(PlanNode node, Set<PlanNode> visited) {
        if (node == null) {
            throw new CostEstimationException("Plan contains a null child");
        }
        if (!visited.add(node)) {
            return new Cost(0, 0);
        }
        Cost result = estimateLocalCost(node);
        if (result == null) {
            throw new CostEstimationException("Cost model returned null local cost");
        }
        var children = node.children();
        if (children == null) {
            throw new CostEstimationException("Plan node returned null children");
        }
        for (PlanNode child : children) {
            result = result.plus(total(child, visited));
        }
        return result;
    }
}
