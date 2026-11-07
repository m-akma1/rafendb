package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.SelectionPlanNode;

public class SelectionPushdownRule implements RewriteRule {

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null) return null;
        return pushdown(root);
    }

    private PlanNode pushdown(PlanNode node) {
        if (node.children().isEmpty()) {
            return node;
        }

        List<PlanNode> rewrittenChildren = new ArrayList<>();
        for (PlanNode child : node.children()) {
            rewrittenChildren.add(pushdown(child));
        }

        PlanNode reconstructedNode = reconstructNode(node, rewrittenChildren);

        if (reconstructedNode instanceof SelectionPlanNode selectionNode) {
            return attemptPushdown(selectionNode);
        }

        return reconstructedNode;
    }

    private PlanNode reconstructNode(PlanNode original, List<PlanNode> newChildren) {
        return original; 
    }

    private PlanNode attemptPushdown(SelectionPlanNode selectionNode) {
        return selectionNode;
    }
}