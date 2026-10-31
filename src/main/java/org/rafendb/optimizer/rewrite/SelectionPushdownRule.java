package org.rafendb.optimizer.rewrite;

import org.rafendb.common.plan.PlanNode;

public class SelectionPushdownRule implements RewriteRule {

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null) return null;
        return pushdown(root);
    }

    private PlanNode pushdown(PlanNode node) {
        return node;
    }
}
