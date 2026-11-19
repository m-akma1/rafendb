package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;

/**
 * Builds equivalent left-deep joins from permutations of input occurrences.
 * Occurrence IDs distinguish aliases and repeated scans. Collected predicates use offsets
 * in the original join region. A restoring projection preserves the layout seen by ancestors.
 * Input plans must already pass PlanValidator.
 */
final class JoinOrderDecoder {
    private final PlanNode original;
    private final PlanNode region;
    private final StorageManager storage;
    private final List<Expr.ColumnRef> originalColumns;
    private final List<Input> inputs = new ArrayList<>();
    private final List<Expr> predicates = new ArrayList<>();

    /** Finds the join region below outer selections, projections and sorts. */
    JoinOrderDecoder(PlanNode original, StorageManager storage) throws CostEstimationException {
        this.original = original;
        this.storage = storage;
        PlanNode current = original;
        while (current instanceof SelectionPlanNode
                || current instanceof ProjectionPlanNode
                || current instanceof SortPlanNode) current = current.children().getFirst();
        region = current;
        originalColumns = knownColumns(region);
        collectInputsAndPredicates(region, 0);
    }

    /** Returns left-to-right occurrence IDs. Decoding them still produces a left-deep tree. */
    List<Integer> originalRelationOrder() {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) order.add(i);
        return List.copyOf(order);
    }

    /** Builds a plan for this permutation and restores its original output layout. */
    PlanNode decode(List<Integer> order) throws CostEstimationException {
        requirePermutation(order);
        int[] originalToDecodedOffsets = new int[originalColumns.size()];
        int decodedWidth = 0;
        for (int id : order) {
            Input input = inputs.get(id);
            for (int column = 0; column < input.width(); column++) {
                originalToDecodedOffsets[input.originalStart() + column] = decodedWidth + column;
            }
            decodedWidth += input.width();
        }

        // Place each predicate once, at the first prefix containing all its inputs.
        boolean[] placed = new boolean[predicates.size()];
        Input first = inputs.get(order.getFirst());
        PlanNode joined = first.plan();
        int joinedWidth = first.width();
        Expr firstCondition =
                takeAvailablePredicates(originalToDecodedOffsets, joinedWidth, placed);
        if (firstCondition != null) joined = new SelectionPlanNode(firstCondition, joined);
        for (int i = 1; i < order.size(); i++) {
            Input next = inputs.get(order.get(i));
            joinedWidth += next.width();
            Expr condition = takeAvailablePredicates(originalToDecodedOffsets, joinedWidth, placed);
            joined = PlanTrees.createJoinOrCartesian(condition, joined, next.plan());
        }
        for (boolean used : placed) {
            if (!used)
                throw new CostEstimationException(
                        "GENETIC decoder could not place every predicate");
        }
        // Restore column order so ancestor offsets stay valid.
        PlanNode restored =
                PlanTrees.restoreOutputOrder(originalColumns, originalToDecodedOffsets, joined);
        return PlanTrees.replace(original, region, restored);
    }

    /** Only joins, Cartesian products and layout-preserving selections can be flattened. */
    private void collectInputsAndPredicates(PlanNode node, int originalStart)
            throws CostEstimationException {
        if (node instanceof JoinPlanNode join) {
            if (join.condition() == null || join.algorithm() != JoinAlgorithm.BLOCK_NESTED_LOOP) {
                throw new CostEstimationException(
                        "GENETIC requires resolved conditional BNL joins");
            }
            collectInputsAndPredicates(join.left(), originalStart);
            collectInputsAndPredicates(
                    join.right(), originalStart + knownColumns(join.left()).size());
            liftPredicates(join.condition(), knownColumns(join), originalStart);
        } else if (node instanceof CartesianPlanNode product) {
            if (product.algorithm() != JoinAlgorithm.BLOCK_NESTED_LOOP) {
                throw new CostEstimationException("GENETIC requires BNL Cartesian products");
            }
            collectInputsAndPredicates(product.left(), originalStart);
            collectInputsAndPredicates(
                    product.right(), originalStart + knownColumns(product.left()).size());
        } else if (node instanceof SelectionPlanNode selection && containsJoin(selection.child())) {
            collectInputsAndPredicates(selection.child(), originalStart);
            liftPredicates(selection.condition(), knownColumns(selection.child()), originalStart);
        } else {
            if (containsJoin(node)) {
                // Flattening here could change projected columns or sort order.
                throw new CostEstimationException(
                        "GENETIC does not flatten projection/sort inside a join input; use HEURISTIC mode");
            }
            inputs.add(new Input(node, originalStart, knownColumns(node).size()));
        }
    }

    /** Moves a local condition into the original region's offsets before splitting AND. */
    private void liftPredicates(
            Expr condition, List<Expr.ColumnRef> localColumns, int originalStart)
            throws CostEstimationException {
        if (!ColumnLayouts.matchesLayout(condition, localColumns)) {
            throw new CostEstimationException(
                    "GENETIC received invalid/unbound predicate: " + condition);
        }
        int[] localToOriginalOffsets = new int[localColumns.size()];
        for (int offset = 0; offset < localToOriginalOffsets.length; offset++) {
            localToOriginalOffsets[offset] = originalStart + offset;
        }
        predicates.addAll(
                PlanTrees.conjuncts(
                        ColumnLayouts.remapColumnOffsets(condition, localToOriginalOffsets)));
    }

    /** Marks available conjuncts as placed and rebinds them to the new layout. */
    private Expr takeAvailablePredicates(
            int[] originalToDecodedOffsets, int joinedWidth, boolean[] placed) {
        Expr result = null;
        for (int i = 0; i < predicates.size(); i++) {
            if (!placed[i]
                    && referencesAvailableInputs(
                            predicates.get(i), originalToDecodedOffsets, joinedWidth)) {
                result =
                        PlanTrees.and(
                                result,
                                ColumnLayouts.remapColumnOffsets(
                                        predicates.get(i), originalToDecodedOffsets));
                placed[i] = true;
            }
        }
        return result;
    }

    /** Constant predicates can be placed at the first input, including FALSE and SQL NULL. */
    private boolean referencesAvailableInputs(
            Expr expression, int[] originalToDecodedOffsets, int joinedWidth) {
        if (expression instanceof Expr.ColumnRef column)
            return originalToDecodedOffsets[column.offset()] < joinedWidth;
        if (expression instanceof Expr.Binary binary) {
            return referencesAvailableInputs(binary.left(), originalToDecodedOffsets, joinedWidth)
                    && referencesAvailableInputs(
                            binary.right(), originalToDecodedOffsets, joinedWidth);
        }
        return true;
    }

    private List<Expr.ColumnRef> knownColumns(PlanNode node) throws CostEstimationException {
        List<Expr.ColumnRef> columns = ColumnLayouts.outputColumns(node, storage);
        if (columns == null || columns.isEmpty())
            throw new CostEstimationException("GENETIC cannot resolve an input layout");
        return columns;
    }

    private boolean containsJoin(PlanNode node) {
        if (node instanceof JoinPlanNode || node instanceof CartesianPlanNode) return true;
        for (PlanNode child : node.children()) if (containsJoin(child)) return true;
        return false;
    }

    private void requirePermutation(List<Integer> order) {
        if (order.size() != inputs.size())
            throw new IllegalArgumentException("Join order has the wrong number of inputs");
        boolean[] seen = new boolean[inputs.size()];
        for (Integer id : order) {
            if (id == null || id < 0 || id >= seen.length || seen[id])
                throw new IllegalArgumentException("Join order is not a permutation");
            seen[id] = true;
        }
    }

    /** One input subtree and its column interval in the original layout. */
    private record Input(PlanNode plan, int originalStart, int width) {}
}
