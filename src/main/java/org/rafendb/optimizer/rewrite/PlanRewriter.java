package org.rafendb.optimizer.rewrite;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.optimizer.catalog.Catalog;

public final class PlanRewriter {
    static final int MAX_PASSES = 8;
    private final List<RewriteRule> rules;

    public PlanRewriter(Catalog catalog) {
        this(
                List.of(
                        new SelectionPushdownRule(Objects.requireNonNull(catalog, "catalog")),
                        new ProjectionPushdownRule(catalog)));
    }

    PlanRewriter(List<RewriteRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public PlanNode rewrite(PlanNode root) {
        PlanNode current = Objects.requireNonNull(root, "root");
        String before = signature(current);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            for (RewriteRule rule : rules) {
                current = rule.apply(current);
            }
            String after = signature(current);
            if (after.equals(before)) break;
            before = after;
        }
        return current;
    }

    private static String signature(PlanNode node) {
        StringBuilder out = new StringBuilder();
        append(node, out);
        return out.toString();
    }

    private static void append(PlanNode node, StringBuilder out) {
        out.append(node.describe()).append('(');
        for (PlanNode child : node.children()) append(child, out);
        out.append(')');
    }
}
