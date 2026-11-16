package org.rafendb.optimizer.explain;

import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.QueryPlan;

public final class PlanExplainer {
    private PlanExplainer() {}

    public static String explain(QueryPlan plan) {
        StringBuilder sb = new StringBuilder();
        explainNode(sb, plan.root(), 0);
        sb.append("Total cost: ").append(plan.totalCost()).append("\n");
        return sb.toString();
    }

    private static void explainNode(StringBuilder sb, PlanNode node, int depth) {
        sb.append(" ".repeat(depth));
        if (depth > 0) {
            sb.append("-> ");
        }
        sb.append(node.describe()).append("\n");
        for (PlanNode child : node.children()) {
            explainNode(sb, child, depth + 1);
        }
    }
}
