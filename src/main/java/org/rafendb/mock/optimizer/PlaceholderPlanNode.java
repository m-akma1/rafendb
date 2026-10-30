package org.rafendb.mock.optimizer;

import java.util.List;
import org.rafendb.common.plan.PlanNode;

/**
 * Minimal leaf {@link PlanNode} with a fixed description and no children.
 * Replace with real plan nodes (table scan, selection, join, ...).
 */
public final class PlaceholderPlanNode implements PlanNode {
    private final String description;

    PlaceholderPlanNode(String description) {
        this.description = description;
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return description;
    }
}
