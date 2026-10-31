package org.rafendb.optimizer.rewrite;

import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.plan.ProjectionPlanNode;

public class ProjectionPushdownRule implements RewriteRule {

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null) return null;
        Set<String> requiredColumns = extractRequiredColumns(root);
        return pushdown(root, requiredColumns);
    }

    private PlanNode pushdown(PlanNode node, Set<String> requiredColumns) {
        if (node instanceof TableScanNode scan) {
            List<Expr> columns = new ArrayList<>();
            return new ProjectionPlanNode(columns, scan);
        }

        Set<String> columnsForCurrentNode = getRequiredColumnsForNode(node);
        requiredColumns.addAll(columnsForCurrentNode);

        return node;
    }

    private Set<String> extractRequiredColumns(PlanNode root) {
        return new HashSet<>();
    }

    private Set<String> getRequiredColumnsForNode(PlanNode node) {
        return new HashSet<>();
    }
}
