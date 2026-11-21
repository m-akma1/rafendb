package org.rafendb.optimizer.rewrite;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.optimizer.catalog.Catalog;

public final class PlanRewriter {
    private final List<RewriteRule> rules;

    public PlanRewriter(Catalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        this.rules =
                List.of(new SelectionPushdownRule(catalog), new ProjectionPushdownRule(catalog));
    }

    public PlanNode rewrite(PlanNode root) {
        PlanNode current = Objects.requireNonNull(root, "root");
        for (RewriteRule rule : rules) {
            current = rule.apply(current);
        }
        return current;
    }
}
