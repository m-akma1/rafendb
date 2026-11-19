package org.rafendb.optimizer.join;

import static org.rafendb.optimizer.join.ColumnLayouts.matchesLayout;
import static org.rafendb.optimizer.join.ColumnLayouts.outputColumns;
import static org.rafendb.optimizer.join.ColumnLayouts.remapColumnOffsets;
import static org.rafendb.optimizer.join.PlanTrees.createJoinOrCartesian;

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

/** Normalizes join expressions and generates equivalent join arrangements. */
public final class JoinOptimizer {
    public JoinOptimizer() {}

    /**
     * Converts eligible Cartesian products and merges selections into joins.
     *
     * @param root the input plan
     * @return the normalized plan root
     */
    public PlanNode normalize(PlanNode root) {
        return normalize(root, null);
    }

    /** Uses the catalog, when available, to recognize bound unqualified connecting predicates. */
    PlanNode normalize(PlanNode root, StorageManager storage) {
        return rewriteSelectionsIntoJoins(Objects.requireNonNull(root, "root"), storage);
    }

    /**
     * Returns the original plan and alternatives with one swap or three-input regrouping.
     * Only conditional BNL joins and explicit Cartesian products are reordered.
     * Unresolved natural joins are left unchanged.
     *
     * @param root the original plan
     * @param storage schema information for resolving columns
     * @return the original plan and its layout-preserving alternatives
     */
    public List<PlanNode> generateCandidates(PlanNode root, StorageManager storage) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(storage, "storage");
        List<PlanNode> candidates = new ArrayList<>();
        candidates.add(root);
        collectJoinAlternatives(root, root, storage, candidates);
        return List.copyOf(candidates);
    }

    /** Visits the source tree only. The search combines successive transformations. */
    private void collectJoinAlternatives(
            PlanNode root, PlanNode current, StorageManager storage, List<PlanNode> candidates) {
        Inputs join = reorderableInputs(current);
        if (join != null) {
            PlanNode swapped = swap(join, storage);
            if (swapped != null) candidates.add(PlanTrees.replace(root, current, swapped));
            PlanNode rightGrouped = regroup(join, storage, true);
            if (rightGrouped != null)
                candidates.add(PlanTrees.replace(root, current, rightGrouped));
            PlanNode leftGrouped = regroup(join, storage, false);
            if (leftGrouped != null) candidates.add(PlanTrees.replace(root, current, leftGrouped));
        }
        for (PlanNode child : PlanTrees.children(current)) {
            collectJoinAlternatives(root, child, storage, candidates);
        }
    }

    /** A restoring projection preserves output column order after swapping inputs. */
    private PlanNode swap(Inputs join, StorageManager storage) {
        List<Expr.ColumnRef> left = outputColumns(join.left(), storage);
        List<Expr.ColumnRef> right = outputColumns(join.right(), storage);
        if (left == null || right == null) return null;
        List<Expr.ColumnRef> original = new ArrayList<>(left);
        original.addAll(right);
        if (join.condition() != null && !matchesLayout(join.condition(), original)) return null;
        int[] originalToSwappedOffsets = new int[original.size()];
        for (int i = 0; i < left.size(); i++) originalToSwappedOffsets[i] = right.size() + i;
        for (int i = 0; i < right.size(); i++) originalToSwappedOffsets[left.size() + i] = i;
        Expr condition =
                join.condition() == null
                        ? null
                        : remapColumnOffsets(join.condition(), originalToSwappedOffsets);
        PlanNode swapped = createJoinOrCartesian(condition, join.right(), join.left());
        return PlanTrees.restoreOutputOrder(original, originalToSwappedOffsets, swapped);
    }

    private Inputs reorderableInputs(PlanNode node) {
        if (node instanceof JoinPlanNode join
                && join.condition() != null
                && join.algorithm() == JoinAlgorithm.BLOCK_NESTED_LOOP) {
            return new Inputs(join.condition(), join.left(), join.right());
        }
        if (node instanceof CartesianPlanNode cartesian
                && cartesian.algorithm() == JoinAlgorithm.BLOCK_NESTED_LOOP) {
            return new Inputs(null, cartesian.left(), cartesian.right());
        }
        return null;
    }

    /** A null condition represents an explicit Cartesian product. */
    private record Inputs(Expr condition, PlanNode left, PlanNode right) {}

    /**
     * Rotates (A join B) join C to A join (B join C), or the reverse. Both arrangements output ABC,
     * so ancestors need no remapping. Old child predicates first use offsets in the ABC layout.
     * Predicates using only the new child's columns then use that child's local offsets.
     */
    private PlanNode regroup(Inputs join, StorageManager storage, boolean toRight) {
        Inputs nested = reorderableInputs(toRight ? join.left() : join.right());
        if (nested == null) return null;
        PlanNode firstInput = toRight ? nested.left() : join.left();
        PlanNode middleInput = toRight ? nested.right() : nested.left();
        PlanNode lastInput = toRight ? join.right() : nested.right();
        List<Expr.ColumnRef> firstColumns = outputColumns(firstInput, storage);
        List<Expr.ColumnRef> middleColumns = outputColumns(middleInput, storage);
        List<Expr.ColumnRef> lastColumns = outputColumns(lastInput, storage);
        if (firstColumns == null || middleColumns == null || lastColumns == null) return null;
        List<Expr.ColumnRef> parentColumns = new ArrayList<>(firstColumns);
        parentColumns.addAll(middleColumns);
        parentColumns.addAll(lastColumns);
        int oldChildStart = toRight ? 0 : firstColumns.size();
        int oldChildEnd =
                toRight ? firstColumns.size() + middleColumns.size() : parentColumns.size();
        if ((join.condition() != null && !matchesLayout(join.condition(), parentColumns))
                || (nested.condition() != null
                        && !matchesLayout(
                                nested.condition(),
                                parentColumns.subList(oldChildStart, oldChildEnd)))) {
            return null;
        }

        // Compare predicates in one ABC layout before choosing their new operator.
        int[] childToParentOffsets = new int[oldChildEnd - oldChildStart];
        for (int offset = 0; offset < childToParentOffsets.length; offset++) {
            childToParentOffsets[offset] = oldChildStart + offset;
        }
        List<Expr> predicates = new ArrayList<>();
        if (nested.condition() != null) {
            predicates.addAll(
                    PlanTrees.conjuncts(
                            remapColumnOffsets(nested.condition(), childToParentOffsets)));
        }
        if (join.condition() != null) predicates.addAll(PlanTrees.conjuncts(join.condition()));

        int newChildStart = toRight ? firstColumns.size() : 0;
        int newChildEnd =
                toRight ? parentColumns.size() : firstColumns.size() + middleColumns.size();
        int[] parentToChildOffsets = new int[parentColumns.size()];
        for (int offset = newChildStart; offset < newChildEnd; offset++) {
            parentToChildOffsets[offset] = offset - newChildStart;
        }
        Expr childCondition = null;
        Expr parentCondition = null;
        for (Expr predicate : predicates) {
            // Only predicates wholly inside the new child can use this partial offset map.
            // Constants stay at the parent so regrouping does not change their placement.
            if (allColumnOffsetsInRange(predicate, newChildStart, newChildEnd)
                    && containsColumnReference(predicate)) {
                childCondition =
                        PlanTrees.and(
                                childCondition,
                                remapColumnOffsets(predicate, parentToChildOffsets));
            } else {
                parentCondition = PlanTrees.and(parentCondition, predicate);
            }
        }
        return toRight
                ? createJoinOrCartesian(
                        parentCondition,
                        firstInput,
                        createJoinOrCartesian(childCondition, middleInput, lastInput))
                : createJoinOrCartesian(
                        parentCondition,
                        createJoinOrCartesian(childCondition, firstInput, middleInput),
                        lastInput);
    }

    /** Constants have no offsets, so they pass this half-open range check. */
    private boolean allColumnOffsetsInRange(Expr expr, int start, int end) {
        if (expr instanceof Expr.ColumnRef column) {
            return column.offset() >= start && column.offset() < end;
        }
        if (expr instanceof Expr.Binary binary) {
            return allColumnOffsetsInRange(binary.left(), start, end)
                    && allColumnOffsetsInRange(binary.right(), start, end);
        }
        return true;
    }

    private boolean containsColumnReference(Expr expr) {
        return expr instanceof Expr.ColumnRef
                || expr instanceof Expr.Binary binary
                        && (containsColumnReference(binary.left())
                                || containsColumnReference(binary.right()));
    }

    /** Normalization preserves output layout, so ancestor offsets stay valid. */
    private PlanNode rewriteSelectionsIntoJoins(PlanNode current, StorageManager storage) {
        if (current instanceof SelectionPlanNode selection) {
            PlanNode child = rewriteSelectionsIntoJoins(selection.child(), storage);
            return rewriteSelectionOverJoin(
                    child == selection.child()
                            ? selection
                            : new SelectionPlanNode(selection.condition(), child),
                    storage);
        }
        if (current instanceof ProjectionPlanNode projection) {
            PlanNode child = rewriteSelectionsIntoJoins(projection.child(), storage);
            return child == projection.child()
                    ? projection
                    : new ProjectionPlanNode(projection.columns(), child);
        }
        if (current instanceof SortPlanNode sort) {
            PlanNode child = rewriteSelectionsIntoJoins(sort.child(), storage);
            return child == sort.child()
                    ? sort
                    : new SortPlanNode(child, sort.column(), sort.direction());
        }
        if (current instanceof CartesianPlanNode cartesian) {
            PlanNode left = rewriteSelectionsIntoJoins(cartesian.left(), storage);
            PlanNode right = rewriteSelectionsIntoJoins(cartesian.right(), storage);
            return left == cartesian.left() && right == cartesian.right()
                    ? cartesian
                    : new CartesianPlanNode(cartesian.algorithm(), left, right);
        }
        if (current instanceof JoinPlanNode join) {
            PlanNode left = rewriteSelectionsIntoJoins(join.left(), storage);
            PlanNode right = rewriteSelectionsIntoJoins(join.right(), storage);
            return left == join.left() && right == join.right()
                    ? join
                    : new JoinPlanNode(join.algorithm(), join.condition(), left, right);
        }
        return current;
    }

    /** Merges filters without treating unresolved natural joins as Cartesian products. */
    private PlanNode rewriteSelectionOverJoin(SelectionPlanNode selection, StorageManager storage) {
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

        Set<String> leftNames = new HashSet<>();
        Set<String> rightNames = new HashSet<>();
        collectInputQualifiers(cartesian.left(), leftNames);
        collectInputQualifiers(cartesian.right(), rightNames);
        List<Expr.ColumnRef> layout = storage == null ? null : outputColumns(cartesian, storage);
        List<Expr.ColumnRef> leftColumns =
                storage == null ? null : outputColumns(cartesian.left(), storage);
        for (Expr conjunct : PlanTrees.conjuncts(selection.condition())) {
            boolean boundConnection =
                    layout != null
                            && leftColumns != null
                            && matchesLayout(conjunct, layout)
                            && !allColumnOffsetsInRange(conjunct, 0, leftColumns.size())
                            && !allColumnOffsetsInRange(
                                    conjunct, leftColumns.size(), layout.size());
            if (boundConnection || referencesBothInputs(conjunct, leftNames, rightNames)) {
                return new JoinPlanNode(
                        cartesian.algorithm(),
                        selection.condition(),
                        cartesian.left(),
                        cartesian.right());
            }
        }
        return selection;
    }

    /** Each referenced qualifier must belong to exactly one input. */
    private boolean referencesBothInputs(Expr expr, Set<String> left, Set<String> right) {
        Set<String> references = new HashSet<>();
        collectReferencedQualifiers(expr, references);
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

    /** A null qualifier marks an unqualified column. */
    private void collectReferencedQualifiers(Expr expr, Set<String> references) {
        if (expr instanceof Expr.ColumnRef column) {
            references.add(column.table());
        } else if (expr instanceof Expr.Binary binary) {
            collectReferencedQualifiers(binary.left(), references);
            collectReferencedQualifiers(binary.right(), references);
        }
    }

    private void collectInputQualifiers(PlanNode node, Set<String> names) {
        if (node instanceof TableScanNode scan) {
            names.add(scan.alias() == null ? scan.table() : scan.alias());
        } else if (node instanceof IndexScanNode scan) {
            names.add(scan.alias() == null ? scan.table() : scan.alias());
        } else {
            for (PlanNode child : node.children()) {
                collectInputQualifiers(child, names);
            }
        }
    }
}
