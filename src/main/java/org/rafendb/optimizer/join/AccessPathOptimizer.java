package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.*;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;

/** Generates single-index alternatives. */
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
        collectCandidates(root, root, candidates);
        return List.copyOf(candidates);
    }

    private void collectCandidates(PlanNode root, PlanNode current, List<PlanNode> candidates) {
        if (current instanceof SelectionPlanNode selection
                && selection.child() instanceof TableScanNode scan) {
            TableSchema schema = storage.getSchema(scan.table());
            List<IndexInfo> indexes = storage.listIndexes(scan.table());
            List<Expr> predicates = PlanTrees.conjuncts(selection.condition());

            for (int i = 0; i < predicates.size(); i++) {
                Expr.Binary condition = indexCondition(predicates.get(i), scan, schema);
                if (condition == null) continue;
                Expr.ColumnRef column = (Expr.ColumnRef) condition.left();

                for (IndexInfo index : indexes) {
                    if (!index.table().equals(scan.table())
                            || !index.column().equals(column.column())) continue;

                    if (condition.op() != Expr.Op.EQ && !index.type().supportsRange()) continue;

                    PlanNode alternative =
                            new IndexScanNode(
                                    scan.alias(),
                                    scan.table(),
                                    new IndexScanDescriptor(
                                            index.name(), index.column(), condition));

                    Expr residual = null;
                    for (int j = 0; j < predicates.size(); j++) {
                        if (j != i) residual = PlanTrees.and(residual, predicates.get(j));
                    }
                    if (residual != null) {
                        alternative = new SelectionPlanNode(residual, alternative);
                    }

                    candidates.add(PlanTrees.replace(root, selection, alternative));
                }
            }
        }
        for (PlanNode child : PlanTrees.children(current)) {
            collectCandidates(root, child, candidates);
        }
    }

    /** Normalizes supported comparisons to column OP literal for index lookup. */
    private Expr.Binary indexCondition(Expr expr, TableScanNode scan, TableSchema schema) {
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
        if (column.offset() >= 0 && column.offset() != offset) return null;
        if (column.table() == null && column.offset() < 0) return null;

        Expr.ColumnRef bound = new Expr.ColumnRef(column.table(), column.column(), offset);
        return new Expr.Binary(op, bound, literal);
    }
}
