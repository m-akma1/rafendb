package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;

/** Finds eligible index scans for a query plan. */
public final class AccessPathOptimizer {
    private final StorageManager storage;

    public AccessPathOptimizer(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Returns the original plan and alternatives with one index-scan replacement each.
     *
     * @param root the input plan
     * @return the original plan and valid access-path alternatives
     */
    public List<PlanNode> generateCandidates(PlanNode root) {
        Objects.requireNonNull(root, "root");
        List<PlanNode> candidates = new ArrayList<>();
        candidates.add(root);
        collectIndexAlternatives(root, root, candidates);
        return List.copyOf(candidates);
    }

    /** Replaces index scans with table scans, retaining each lookup condition as a filter. */
    PlanNode withSequentialScans(PlanNode root) {
        if (root instanceof IndexScanNode scan) {
            return new SelectionPlanNode(
                    scan.descriptor().condition(), new TableScanNode(scan.alias(), scan.table()));
        }
        PlanNode result = root;
        for (PlanNode child : PlanTrees.children(root)) {
            PlanNode sequential = withSequentialScans(child);
            if (sequential != child) result = PlanTrees.replace(result, child, sequential);
        }
        return result;
    }

    private void collectIndexAlternatives(
            PlanNode root, PlanNode current, List<PlanNode> candidates) {
        if (current instanceof SelectionPlanNode selection) {
            collectSelectionIndexAlternatives(root, selection, candidates);
        } else if (current instanceof JoinPlanNode join
                && join.condition() != null
                && join.algorithm() == JoinAlgorithm.BLOCK_NESTED_LOOP) {
            List<Expr.ColumnRef> left = ColumnLayouts.outputColumns(join.left(), storage);
            List<Expr.ColumnRef> layout = ColumnLayouts.outputColumns(join, storage);
            if (left != null && layout != null) {
                collectJoinIndexAlternatives(root, join, true, 0, candidates);
                collectJoinIndexAlternatives(root, join, false, left.size(), candidates);
            }
        }
        for (PlanNode child : PlanTrees.children(current)) {
            collectIndexAlternatives(root, child, candidates);
        }
    }

    /** Combines adjacent selections while preserving every predicate the index does not answer. */
    private void collectSelectionIndexAlternatives(
            PlanNode root, SelectionPlanNode selection, List<PlanNode> candidates) {
        List<Expr> predicates = new ArrayList<>(PlanTrees.conjuncts(selection.condition()));
        PlanNode child = selection.child();
        while (child instanceof SelectionPlanNode nested) {
            predicates.addAll(PlanTrees.conjuncts(nested.condition()));
            child = nested.child();
        }
        if (!(child instanceof TableScanNode scan)) return;
        for (int i = 0; i < predicates.size(); i++) {
            for (IndexScanNode indexed : eligibleIndexScans(scan, predicates.get(i), 0)) {
                Expr residual = remainingPredicatesExcept(predicates, i);
                PlanNode alternative =
                        residual == null ? indexed : new SelectionPlanNode(residual, indexed);
                candidates.add(PlanTrees.replace(root, selection, alternative));
            }
        }
    }

    /**
     * Finds local filters merged into a join condition, using direct table inputs or filtered scans.
     * Only the indexed conjunct moves. All other join and input predicates stay in place.
     */
    private void collectJoinIndexAlternatives(
            PlanNode root,
            JoinPlanNode join,
            boolean onLeft,
            int start,
            List<PlanNode> candidates) {
        PlanNode input = onLeft ? join.left() : join.right();
        PlanNode leaf = input;
        while (leaf instanceof SelectionPlanNode selection) leaf = selection.child();
        if (!(leaf instanceof TableScanNode scan)) return;
        List<Expr> predicates = PlanTrees.conjuncts(join.condition());
        for (int i = 0; i < predicates.size(); i++) {
            for (IndexScanNode indexed : eligibleIndexScans(scan, predicates.get(i), start)) {
                PlanNode indexedInput = PlanTrees.replace(input, scan, indexed);
                PlanNode left = onLeft ? indexedInput : join.left();
                PlanNode right = onLeft ? join.right() : indexedInput;
                Expr residual = remainingPredicatesExcept(predicates, i);
                PlanNode alternative =
                        residual == null
                                ? new CartesianPlanNode(join.algorithm(), left, right)
                                : new JoinPlanNode(join.algorithm(), residual, left, right);
                candidates.add(PlanTrees.replace(root, join, alternative));
            }
        }
    }

    private List<IndexScanNode> eligibleIndexScans(
            TableScanNode scan, Expr predicate, int inputStart) {
        Expr.Binary condition =
                eligibleIndexLookupCondition(
                        predicate, scan, storage.getSchema(scan.table()), inputStart);
        if (condition == null) return List.of();
        Expr.ColumnRef column = (Expr.ColumnRef) condition.left();
        List<IndexScanNode> result = new ArrayList<>();
        for (IndexInfo index : storage.listIndexes(scan.table())) {
            if (index.table().equals(scan.table())
                    && index.column().equals(column.column())
                    && (condition.op() == Expr.Op.EQ || index.type().supportsRange())) {
                result.add(
                        new IndexScanNode(
                                scan.alias(),
                                scan.table(),
                                new IndexScanDescriptor(index.name(), index.column(), condition)));
            }
        }
        return result;
    }

    private static Expr remainingPredicatesExcept(List<Expr> predicates, int handled) {
        Expr residual = null;
        for (int i = 0; i < predicates.size(); i++) {
            if (i != handled) residual = PlanTrees.and(residual, predicates.get(i));
        }
        return residual;
    }

    /** Uses the same eligibility rules as generated index scans. */
    boolean isEligibleIndexScan(IndexScanNode scan) {
        TableScanNode table = new TableScanNode(scan.alias(), scan.table());
        IndexScanDescriptor requested = scan.descriptor();
        for (IndexScanNode candidate : eligibleIndexScans(table, requested.condition(), 0)) {
            IndexScanDescriptor available = candidate.descriptor();
            if (available.indexName().equals(requested.indexName())
                    && available.column().equals(requested.column())) return true;
        }
        return false;
    }

    /** Checks a column-to-literal comparison at inputStart + schema offset, then makes it scan-local. */
    private static Expr.Binary eligibleIndexLookupCondition(
            Expr expr, TableScanNode scan, TableSchema schema, int inputStart) {
        if (!(expr instanceof Expr.Binary binary)) return null;

        Expr left = binary.left();
        Expr right = binary.right();
        Expr.Op op = binary.op();

        if (left instanceof Expr.Literal && right instanceof Expr.ColumnRef) {
            left = binary.right();
            right = binary.left();
            op =
                    switch (op) {
                        case GT -> Expr.Op.LT;
                        case GE -> Expr.Op.LE;
                        case LT -> Expr.Op.GT;
                        case LE -> Expr.Op.GE;
                        default -> op;
                    };
        }

        if (op != Expr.Op.EQ
                && op != Expr.Op.GT
                && op != Expr.Op.GE
                && op != Expr.Op.LT
                && op != Expr.Op.LE) return null;

        if (!(left instanceof Expr.ColumnRef column)
                || !(right instanceof Expr.Literal literal)
                || literal.value().isNull()) return null;

        String qualifier = scan.alias() == null ? scan.table() : scan.alias();
        if (column.table() != null && !column.table().equals(qualifier)) return null;

        int offset = schema.columnIndex(column.column());
        if (offset < 0) return null;

        var columnType = schema.columns().get(offset).type();
        if (columnType != literal.value().type()
                && !(columnType.isText() && literal.value().type().isText())) return null;
        if (column.offset() != inputStart + offset) return null;

        Expr.ColumnRef bound = new Expr.ColumnRef(column.table(), column.column(), offset);
        return new Expr.Binary(op, bound, literal);
    }
}
