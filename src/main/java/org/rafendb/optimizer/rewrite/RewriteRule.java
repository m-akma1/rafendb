package org.rafendb.optimizer.rewrite;

import org.rafendb.common.plan.PlanNode;

public interface RewriteRule {
    /**
     * @param node The root of the plan tree (or subtree) to rewrite.
     * @return The rewritten plan tree.
     */
    PlanNode apply(PlanNode node);
}