package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.JoinAlgorithm;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.ProjectionPlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.schema.Column;

/**
 * Normalizes join expressions and generates equivalent join arrangements.
 */
public final class JoinOptimizer {
    /**
     * Converts eligible Cartesian products and merges selections into joins.
     *
     * @param root the input plan
     * @return the normalized plan root
     */
    public PlanNode normalize(PlanNode root) {
        return normalizeTree(Objects.requireNonNull(root, "root"));
    }

    /**
     * Generates equivalent plans with one join swap per candidate.
     *
     * @param root the original plan
     * @param storage schema information for resolving columns
     * @return the original plan and its join-swap alternatives
     */
    public List<PlanNode> generateCandidates(PlanNode root, StorageManager storage) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(storage, "storage");
        List<PlanNode> candidates = new ArrayList<>();
        candidates.add(root);
        collectCandidates(root, root, storage, candidates);
        return List.copyOf(candidates);
    }

    /** Visits only the original tree, each returned plan contains one local swap. */
    private void collectCandidates(
            PlanNode root, PlanNode current, StorageManager storage, List<PlanNode> candidates) {
        if (current instanceof JoinPlanNode join && join.condition() != null) {
            PlanNode swapped = swap(join, storage);
            if (swapped != null) candidates.add(PlanTrees.replace(root, join, swapped));
        }
        for (PlanNode child : PlanTrees.children(current)) {
            collectCandidates(root, child, storage, candidates);
        }
    }

    /** Swaps join inputs while preserving the subtree's output column order. */
    private PlanNode swap(JoinPlanNode join, StorageManager storage) {
        List<Expr.ColumnRef> left = outputColumns(join.left(), storage);
        List<Expr.ColumnRef> right = outputColumns(join.right(), storage);
        if (left == null || right == null) return null;
        List<Expr.ColumnRef> original = new ArrayList<>(left);
        original.addAll(right);
        if (!matchesLayout(join.condition(), original)) return null;
        int[] positions = new int[original.size()];
        for (int i = 0; i < left.size(); i++) positions[i] = right.size() + i;
        for (int i = 0; i < right.size(); i++) positions[left.size() + i] = i;
        Expr condition = remap(join.condition(), positions);
        if (!canExecute(join.algorithm(), condition, right.size())) return null;
        JoinPlanNode swapped =
                new JoinPlanNode(join.algorithm(), condition, join.right(), join.left());
        List<Expr> restore = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) {
            Expr.ColumnRef column = original.get(i);
            restore.add(new Expr.ColumnRef(column.table(), column.column(), positions[i]));
        }
        return new ProjectionPlanNode(restore, swapped);
    }

    /** Resolves a single-relation input layout, joins and unknown/derived schemas return null. */
    private List<Expr.ColumnRef> outputColumns(PlanNode node, StorageManager storage) {
        String table;
        String qualifier;
        if (node instanceof TableScanNode scan) {
            table = scan.table();
            qualifier = scan.alias() == null ? table : scan.alias();
        } else if (node instanceof IndexScanNode scan) {
            table = scan.table();
            qualifier = scan.alias() == null ? table : scan.alias();
        } else if (node instanceof SelectionPlanNode selection) {
            return outputColumns(selection.child(), storage);
        } else if (node instanceof SortPlanNode sort) {
            return outputColumns(sort.child(), storage);
        } else if (node instanceof ProjectionPlanNode projection) {
            List<Expr.ColumnRef> input = outputColumns(projection.child(), storage);
            if (input == null) return null;
            List<Expr.ColumnRef> result = new ArrayList<>();
            for (Expr expr : projection.columns()) {
                if (!(expr instanceof Expr.ColumnRef column) || !matchesLayout(column, input))
                    return null;
                result.add(input.get(column.offset()));
            }
            return result;
        } else {
            return null;
        }
        List<Expr.ColumnRef> result = new ArrayList<>();
        for (Column column : storage.getSchema(table).columns()) {
            result.add(new Expr.ColumnRef(qualifier, column.name()));
        }
        return result;
    }

    /** Rebinds offsets after matchesLayout has validated the original references. */
    private Expr remap(Expr expr, int[] positions) {
        if (expr instanceof Expr.ColumnRef column) {
            return new Expr.ColumnRef(column.table(), column.column(), positions[column.offset()]);
        }
        if (expr instanceof Expr.Binary binary) {
            Expr left = remap(binary.left(), positions);
            Expr right = remap(binary.right(), positions);
            return new Expr.Binary(binary.op(), left, right);
        }
        return expr;
    }

    /** Rejects layouts that cannot be reconciled with existing bound column identities. */
    private boolean matchesLayout(Expr expr, List<Expr.ColumnRef> columns) {
        if (expr instanceof Expr.ColumnRef column) {
            if (column.offset() < 0 || column.offset() >= columns.size()) return false;
            Expr.ColumnRef expected = columns.get(column.offset());
            return column.column().equals(expected.column())
                    && (column.table() == null || column.table().equals(expected.table()));
        }
        if (expr instanceof Expr.Binary binary) {
            return matchesLayout(binary.left(), columns) && matchesLayout(binary.right(), columns);
        }
        return true;
    }

    /** Hash/merge alternatives need a simple equijoin key, nested loops accept theta predicates. */
    private boolean canExecute(JoinAlgorithm algorithm, Expr condition, int split) {
        if (algorithm == JoinAlgorithm.NESTED_LOOP || algorithm == JoinAlgorithm.BLOCK_NESTED_LOOP)
            return true;
        for (Expr predicate : PlanTrees.conjuncts(condition)) {
            if (predicate instanceof Expr.Binary binary
                    && binary.op() == Expr.Op.EQ
                    && binary.left() instanceof Expr.ColumnRef left
                    && binary.right() instanceof Expr.ColumnRef right
                    && (left.offset() < split) != (right.offset() < split)) {
                return true;
            }
        }
        return false;
    }

    private PlanNode normalizeTree(PlanNode current) {
        if (current instanceof SelectionPlanNode selection) {
            PlanNode child = normalizeTree(selection.child());
            return normalizeCartesian(
                    child == selection.child()
                            ? selection
                            : new SelectionPlanNode(selection.condition(), child));
        }
        if (current instanceof ProjectionPlanNode projection) {
            PlanNode child = normalizeTree(projection.child());
            return child == projection.child()
                    ? projection
                    : new ProjectionPlanNode(projection.columns(), child);
        }
        if (current instanceof SortPlanNode sort) {
            PlanNode child = normalizeTree(sort.child());
            return child == sort.child()
                    ? sort
                    : new SortPlanNode(child, sort.column(), sort.direction());
        }
        if (current instanceof CartesianPlanNode cartesian) {
            PlanNode left = normalizeTree(cartesian.left());
            PlanNode right = normalizeTree(cartesian.right());
            return left == cartesian.left() && right == cartesian.right()
                    ? cartesian
                    : new CartesianPlanNode(cartesian.algorithm(), left, right);
        }
        if (current instanceof JoinPlanNode join) {
            PlanNode left = normalizeTree(join.left());
            PlanNode right = normalizeTree(join.right());
            return left == join.left() && right == join.right()
                    ? join
                    : new JoinPlanNode(join.algorithm(), join.condition(), left, right);
        }
        return current;
    }

    private PlanNode normalizeCartesian(SelectionPlanNode selection) {
        // Merge a selection into an existing conditional join.
        if (selection.child() instanceof JoinPlanNode join && join.condition() != null) {
            return new JoinPlanNode(
                    join.algorithm(),
                    PlanTrees.and(selection.condition(), join.condition()),
                    join.left(),
                    join.right());
        }
        if (!(selection.child() instanceof CartesianPlanNode cartesian)) {
            return selection;
        }

        // Convert Cartesian to a join when a predicate connects both inputs.
        Set<String> leftNames = new HashSet<>();
        Set<String> rightNames = new HashSet<>();
        collectRelations(cartesian.left(), leftNames);
        collectRelations(cartesian.right(), rightNames);
        for (Expr conjunct : PlanTrees.conjuncts(selection.condition())) {
            if (connects(conjunct, leftNames, rightNames)) {
                return new JoinPlanNode(
                        cartesian.algorithm(),
                        selection.condition(),
                        cartesian.left(),
                        cartesian.right());
            }
        }
        return selection;
    }

    /** Checks that a predicate references both inputs without ambiguous qualifiers. */
    private boolean connects(Expr expr, Set<String> left, Set<String> right) {
        Set<String> references = new HashSet<>();
        collectColumnRelations(expr, references);
        boolean referencesLeft = false;
        boolean referencesRight = false;
        for (String name : references) {
            boolean inLeft = left.contains(name);
            boolean inRight = right.contains(name);
            if (inLeft == inRight) return false;
            if (inLeft) {
                referencesLeft = true;
            } else {
                referencesRight = true;
            }
        }
        return referencesLeft && referencesRight;
    }

    /** Adds all column qualifiers in an expression, null records an unqualified reference. */
    private void collectColumnRelations(Expr expr, Set<String> references) {
        if (expr instanceof Expr.ColumnRef column) {
            references.add(column.table());
        } else if (expr instanceof Expr.Binary binary) {
            collectColumnRelations(binary.left(), references);
            collectColumnRelations(binary.right(), references);
        }
    }

    /** Collects relation qualifiers from table and index scans. */
    private void collectRelations(PlanNode node, Set<String> names) {
        if (node instanceof TableScanNode scan) {
            names.add(scan.alias() == null ? scan.table() : scan.alias());
        } else if (node instanceof IndexScanNode scan) {
            names.add(scan.alias() == null ? scan.table() : scan.alias());
        } else {
            for (PlanNode child : node.children()) {
                collectRelations(child, names);
            }
        }
    }
}
