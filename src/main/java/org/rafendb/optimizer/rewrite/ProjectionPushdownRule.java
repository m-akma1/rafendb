package org.rafendb.optimizer.rewrite;

import org.rafendb.common.plan.PlanNode;

public class ProjectionPushdownRule implements RewriteRule {
    @Override
    public PlanNode apply(PlanNode root) {
        return root;
    }
}
