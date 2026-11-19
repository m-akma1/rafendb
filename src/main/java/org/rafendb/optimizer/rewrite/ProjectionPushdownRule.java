package org.rafendb.optimizer.rewrite;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.optimizer.catalog.Catalog;

public final class ProjectionPushdownRule implements RewriteRule {

    private final PlanLayout layout;

    public ProjectionPushdownRule(Catalog catalog) {
        this.layout = new PlanLayout(catalog);
    }

    @Override
    public PlanNode apply(PlanNode root) {
        if (root == null || !PlanLayout.supports(root)) return root;
        return layout.rebind(prune(root, null));
    }

    private PlanNode prune(PlanNode node, List<Expr.ColumnRef> required) {
        if (node instanceof ProjectionPlanNode proj) return pruneProjection(proj, required);
        if (node instanceof SelectionPlanNode sel) {
            List<Expr.ColumnRef> need = union(required, PlanLayout.refs(sel.condition()));
            return new SelectionPlanNode(sel.condition(), prune(sel.child(), need));
        }
        if (node instanceof SortPlanNode sort) {
            List<Expr.ColumnRef> need = union(required, List.of(sort.column()));
            return new SortPlanNode(prune(sort.child(), need), sort.column(), sort.direction());
        }
        if (node instanceof JoinPlanNode join) {
            List<Expr.ColumnRef> need =
                    join.condition() == null
                            ? null
                            : union(required, PlanLayout.refs(join.condition()));
            return new JoinPlanNode(
                    join.algorithm(),
                    join.condition(),
                    pruneSide(join.left(), need),
                    pruneSide(join.right(), need));
        }
        if (node instanceof CartesianPlanNode cart) {
            return new CartesianPlanNode(
                    cart.algorithm(),
                    pruneSide(cart.left(), required),
                    pruneSide(cart.right(), required));
        }
        return node;
    }

    private PlanNode pruneProjection(ProjectionPlanNode proj, List<Expr.ColumnRef> required) {
        List<Expr> columns = proj.columns();
        PlanNode child = proj.child();

        while (child instanceof ProjectionPlanNode inner) {
            List<PlanLayout.Col> innerOut = layout.of(inner);
            List<Expr> composed = new ArrayList<>();
            for (Expr expr : columns) {
                int index = PlanLayout.find(innerOut, (Expr.ColumnRef) expr);
                composed.add(inner.columns().get(index));
            }
            columns = composed;
            child = inner.child();
        }

        if (required != null) columns = keepRequired(columns, child, required);

        List<Expr.ColumnRef> needed = new ArrayList<>();
        for (Expr expr : columns) needed.add((Expr.ColumnRef) expr);
        return new ProjectionPlanNode(columns, prune(child, needed));
    }

    private List<Expr> keepRequired(
            List<Expr> columns, PlanNode child, List<Expr.ColumnRef> required) {
        List<PlanLayout.Col> input = layout.of(child);
        List<PlanLayout.Col> output = new ArrayList<>();
        for (Expr expr : columns) {
            output.add(input.get(PlanLayout.find(input, (Expr.ColumnRef) expr)));
        }
        List<Expr> kept = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            for (Expr.ColumnRef ref : required) {
                if (PlanLayout.find(output, ref) == i) {
                    kept.add(columns.get(i));
                    break;
                }
            }
        }
        if (kept.isEmpty()) kept.add(columns.get(0));
        return kept;
    }

    private PlanNode pruneSide(PlanNode side, List<Expr.ColumnRef> need) {
        if (need == null) return prune(side, null);

        List<PlanLayout.Col> before = layout.of(side);
        List<Expr.ColumnRef> sideNeed = new ArrayList<>();
        for (Expr.ColumnRef ref : need) {
            if (PlanLayout.find(before, ref) >= 0) sideNeed.add(ref);
        }
        if (sideNeed.isEmpty()) {
            PlanLayout.Col first = before.get(0);
            sideNeed.add(new Expr.ColumnRef(first.qualifier(), first.name()));
        }

        PlanNode pruned = prune(side, sideNeed);
        List<PlanLayout.Col> carried = layout.of(pruned);
        TreeSet<Integer> keep = new TreeSet<>();
        for (Expr.ColumnRef ref : sideNeed) {
            int index = PlanLayout.find(carried, ref);
            if (index >= 0) keep.add(index);
        }
        if (keep.size() >= carried.size()) return pruned;

        List<Expr> columns = new ArrayList<>();
        for (int index : keep) {
            PlanLayout.Col col = carried.get(index);
            columns.add(new Expr.ColumnRef(col.qualifier(), col.name()));
        }
        return new ProjectionPlanNode(columns, pruned);
    }

    private static List<Expr.ColumnRef> union(
            List<Expr.ColumnRef> required, List<Expr.ColumnRef> extra) {
        if (required == null) return null;
        List<Expr.ColumnRef> out = new ArrayList<>(required);
        out.addAll(extra);
        return out;
    }
}
