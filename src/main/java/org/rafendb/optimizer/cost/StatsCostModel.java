package org.rafendb.optimizer.cost;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.error.StorageInternalException;
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
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.TableStats;
import org.rafendb.optimizer.catalog.Catalog;

/**
 * Statistics-based cardinality and I/O estimator. Each public call owns its memoization context.
 * Instances are immutable and thread-safe.
 */
public final class StatsCostModel implements CostModel {
    private static final long LIMIT = Long.MAX_VALUE / 4;
    private final Catalog catalog;
    private final CostParameters parameters;

    /**
     * Uses default storage and selectivity assumptions.
     *
     * @param catalog statistics and schema source
     * @throws NullPointerException if {@code catalog} is {@code null}
     */
    public StatsCostModel(Catalog catalog) {
        this(catalog, CostParameters.defaults());
    }

    /**
     * Creates a model with immutable query-independent parameters.
     *
     * @param catalog statistics and schema source
     * @param parameters physical and selectivity assumptions
     * @throws NullPointerException if an argument is {@code null}
     */
    public StatsCostModel(Catalog catalog, CostParameters parameters) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.parameters = java.util.Objects.requireNonNull(parameters, "parameters");
    }

    /** {@inheritDoc} */
    @Override
    public long estimateRows(PlanNode node)
            throws OptimizerInternalException.CostEstimationException {
        return new Context().rows(node);
    }

    /** {@inheritDoc} */
    @Override
    public Cost estimateLocalCost(PlanNode node)
            throws OptimizerInternalException.CostEstimationException {
        Context c = new Context();
        c.rows(node);
        return c.local(node);
    }

    /** {@inheritDoc} */
    @Override
    public Cost estimateTotalCost(PlanNode node)
            throws OptimizerInternalException.CostEstimationException {
        Context c = new Context();
        c.rows(node);
        Set<PlanNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return c.total(node, seen);
    }

    private final class Context {
        final Map<String, TableStats> stats = new java.util.HashMap<>();
        final Map<PlanNode, Long> rows = new IdentityHashMap<>();
        final Map<PlanNode, Long> blocks = new IdentityHashMap<>();
        final Map<PlanNode, Double> sizes = new IdentityHashMap<>();
        final Map<PlanNode, Cost> locals = new IdentityHashMap<>();
        final Map<PlanNode, Cost> totals = new IdentityHashMap<>();

        TableStats stats(String table) throws OptimizerInternalException.CostEstimationException {
            String key = table;
            TableStats cached = stats.get(key);
            if (cached != null) return cached;
            try {
                TableStats loaded = catalog.getStats(table);
                stats.put(key, loaded);
                return loaded;
            } catch (StorageInternalException e) {
                throw new OptimizerInternalException.CostEstimationException(
                        "Could not read statistics for table " + table, e);
            }
        }

        long rows(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            if (node == null) throw fail("null plan node");
            Long saved = rows.get(node);
            if (saved != null) return saved;
            long n;
            if (node instanceof TableScanNode scan) n = stats(scan.table()).recordCount();
            else if (node instanceof IndexScanNode scan) n = stats(scan.table()).recordCount();
            else if (node instanceof SelectionPlanNode selection)
                n =
                        selected(
                                rows(selection.child()),
                                selectivity(selection.condition(), selection.child()));
            else if (node instanceof ProjectionPlanNode projection) n = rows(projection.child());
            else if (node instanceof SortPlanNode sort) n = rows(sort.child());
            else if (node instanceof JoinPlanNode join)
                n = joined(rows(join.left()), rows(join.right()), join.condition(), node);
            else if (node instanceof CartesianPlanNode cartesian)
                n = safeMultiply(rows(cartesian.left()), rows(cartesian.right()));
            else throw fail("Unknown plan node class " + node.getClass().getName());
            rows.put(node, n);
            return n;
        }

        long blocks(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            Long saved = blocks.get(node);
            if (saved != null) return saved;
            rows(node);
            long b;
            if (node instanceof TableScanNode scan) b = stats(scan.table()).blockCount();
            else if (node instanceof IndexScanNode scan)
                b =
                        rows(node) == 0
                                ? 0
                                : Math.min(
                                        stats(scan.table()).blockCount(),
                                        ceil(rows(node) / factor(node)));
            else if (node instanceof JoinPlanNode join) b = packed(rows(node), size(node));
            else if (node instanceof CartesianPlanNode cartesian)
                b = packed(rows(node), size(node));
            else {
                PlanNode child = node.children().get(0);
                b = Math.min(blocks(child), ceil(rows(node) / factor(node)));
            }
            blocks.put(node, b);
            return b;
        }

        double size(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            Double saved = sizes.get(node);
            if (saved != null) return saved;
            double s;
            if (node instanceof TableScanNode scan) s = recordSize(scan.table());
            else if (node instanceof IndexScanNode scan) s = recordSize(scan.table());
            else if (node instanceof SelectionPlanNode selection || node instanceof SortPlanNode)
                s = size(node.children().get(0));
            else if (node instanceof ProjectionPlanNode projection) {
                double sum = 0;
                for (Expr expr : projection.columns())
                    sum +=
                            expr instanceof Expr.ColumnRef ref
                                    ? columnSize(ref.table(), ref.column())
                                    : 4;
                s = Math.min(size(projection.child()), sum);
            } else if (node instanceof JoinPlanNode join)
                s = size(join.left()) + size(join.right());
            else if (node instanceof CartesianPlanNode cartesian)
                s = size(cartesian.left()) + size(cartesian.right());
            else throw fail("Unknown plan node class " + node.getClass().getName());
            sizes.put(node, s);
            return s;
        }

        Cost total(PlanNode node, Set<PlanNode> seen)
                throws OptimizerInternalException.CostEstimationException {
            if (!seen.add(node)) return new Cost(0, 0);
            Cost cost = local(node);
            for (PlanNode child : node.children()) cost = add(cost, total(child, seen));
            totals.put(node, cost);
            return cost;
        }

        Cost totalSubtree(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            Cost known = totals.get(node);
            if (known != null) return known;
            return total(node, Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        Cost local(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            Cost saved = locals.get(node);
            if (saved != null) return saved;
            rows(node);
            Cost result;
            if (node instanceof TableScanNode scan) {
                long b = stats(scan.table()).blockCount();
                result = new Cost(b > 0 ? 1 : 0, b);
            } else if (node instanceof IndexScanNode scan) {
                TableStats t = stats(scan.table());
                // TODO(contract): IndexScanNode currently has no descriptor or predicate.
                IndexInfo index =
                        catalog.listIndexes(scan.table()).stream().findFirst().orElse(null);
                if (index == null) result = new Cost(t.blockCount() > 0 ? 1 : 0, t.blockCount());
                else {
                    if (index.type() == IndexType.HASH)
                        result =
                                new Cost(
                                        1 + Math.min(rows(node), Math.max(1, t.blockCount())),
                                        1 + Math.min(rows(node), Math.max(1, t.blockCount())));
                    else {
                        double keySize = columnSize(scan.table(), index.column());
                        double fanout =
                                Math.max(
                                        2, Math.floor(parameters.blockSizeBytes() / (keySize + 8)));
                        long height =
                                Math.max(
                                        1,
                                        (long)
                                                Math.ceil(
                                                        Math.log(Math.max(1, t.recordCount()))
                                                                / Math.log(fanout)));
                        long fetched = Math.min(rows(node), Math.max(1, t.blockCount()));
                        result = cost(height + fetched, height + fetched);
                    }
                }
            } else if (node instanceof SelectionPlanNode || node instanceof ProjectionPlanNode)
                result = new Cost(0, 0);
            else if (node instanceof SortPlanNode sort) result = sortCost(blocks(sort.child()));
            else if (node instanceof JoinPlanNode join)
                result = joinCost(join.algorithm(), join.left(), join.right());
            else if (node instanceof CartesianPlanNode cartesian)
                result = joinCost(cartesian.algorithm(), cartesian.left(), cartesian.right());
            else throw fail("Unknown plan node class " + node.getClass().getName());
            locals.put(node, result);
            return result;
        }

        Cost joinCost(JoinAlgorithm algorithm, PlanNode left, PlanNode right)
                throws OptimizerInternalException.CostEstimationException {
            double bo = blocks(left), bi = blocks(right);
            long no = rows(left);
            if (bo == 0 || bi == 0) return new Cost(0, 0);
            return switch (algorithm) {
                case BLOCK_NESTED_LOOP -> {
                    double passes =
                            Math.max(1, Math.ceil(bo / (double) (parameters.bufferBlocks() - 2)));
                    Cost inner = totalSubtree(right);
                    yield cost(
                            (passes - 1) * inner.getSeeks() + passes - 1,
                            (passes - 1) * inner.getTransfers());
                }
                case NESTED_LOOP -> {
                    long repeats = Math.max(0, no - 1);
                    Cost inner = totalSubtree(right);
                    yield cost(
                            repeats * inner.getSeeks() + repeats, repeats * inner.getTransfers());
                }
                case HASH_JOIN -> {
                    if (bi <= parameters.bufferBlocks() - 2) yield new Cost(0, 0);
                    double nh = Math.ceil(bi / (double) (parameters.bufferBlocks() - 1));
                    yield cost(2 * (bo + bi) + 2 * nh, 2 * (bo + bi));
                }
                case SORT_MERGE -> add(sortCostIfNeeded(left), sortCostIfNeeded(right));
            };
        }

        Cost sortCostIfNeeded(PlanNode node)
                throws OptimizerInternalException.CostEstimationException {
            return node instanceof SortPlanNode ? new Cost(0, 0) : sortCost(blocks(node));
        }

        Cost sortCost(long b) {
            int m = parameters.bufferBlocks();
            if (b <= m) return new Cost(0, 0);
            double runs = Math.ceil(b / (double) m);
            double passes = Math.ceil(Math.log(runs) / Math.log(m - 1));
            return cost(2 * runs + b * (2 * passes - 1), 2 * passes * b);
        }

        double selectivity(Expr expr, PlanNode node)
                throws OptimizerInternalException.CostEstimationException {
            if (expr instanceof Expr.Null) return 0;
            if (!(expr instanceof Expr.Binary binary)) return parameters.defaultEqSelectivity();
            if (binary.op() == Expr.Op.AND)
                return selectivity(binary.left(), node) * selectivity(binary.right(), node);
            if (binary.op() == Expr.Op.EQ
                    || binary.op() == Expr.Op.NE
                    || binary.op() == Expr.Op.GT
                    || binary.op() == Expr.Op.GE
                    || binary.op() == Expr.Op.LT
                    || binary.op() == Expr.Op.LE) {
                if (isNullValue(binary.left()) || isNullValue(binary.right())) return 0;
            }
            if (binary.op() == Expr.Op.GT
                    || binary.op() == Expr.Op.GE
                    || binary.op() == Expr.Op.LT
                    || binary.op() == Expr.Op.LE) return parameters.rangeSelectivity();
            if (binary.op() == Expr.Op.NE) return 1 - equality(binary.left(), binary.right(), node);
            if (binary.op() == Expr.Op.EQ) return equality(binary.left(), binary.right(), node);
            return parameters.defaultEqSelectivity();
        }

        boolean isNullValue(Expr expr) {
            if (expr instanceof Expr.Null) return true;
            if (expr instanceof Expr.Literal literal) return literal.value().isNull();
            if (expr instanceof Expr.Binary binary) {
                return binary.op() == Expr.Op.ADD
                                || binary.op() == Expr.Op.SUB
                                || binary.op() == Expr.Op.MUL
                                || binary.op() == Expr.Op.DIV
                                || binary.op() == Expr.Op.MOD
                        ? isNullValue(binary.left()) || isNullValue(binary.right())
                        : false;
            }
            return false;
        }

        double equality(Expr a, Expr b, PlanNode node)
                throws OptimizerInternalException.CostEstimationException {
            if (a instanceof Expr.ColumnRef col && b instanceof Expr.ColumnRef other)
                return 1.0 / Math.max(distinct(col, node), distinct(other, node));
            if (a instanceof Expr.ColumnRef col) return 1.0 / distinct(col, node);
            if (b instanceof Expr.ColumnRef col) return 1.0 / distinct(col, node);
            return parameters.defaultEqSelectivity();
        }

        long distinct(Expr.ColumnRef column, PlanNode node)
                throws OptimizerInternalException.CostEstimationException {
            String table = findTable(node, column.table());
            TableStats t = stats(table);
            Long exact = t.distinctValues().get(column.column());
            long v =
                    exact == null
                            ? t.distinctValues().entrySet().stream()
                                    .filter(e -> e.getKey().equalsIgnoreCase(column.column()))
                                    .map(Map.Entry::getValue)
                                    .findFirst()
                                    .orElse(1L)
                            : exact;
            PlanNode relation = relationNode(node, table);
            return Math.max(1, Math.min(v, Math.max(1, rows(relation))));
        }

        boolean hasTable(PlanNode node, String name) {
            if (node instanceof TableScanNode s)
                return s.table().equalsIgnoreCase(name)
                        || s.alias() != null && s.alias().equalsIgnoreCase(name);
            if (node instanceof IndexScanNode s)
                return s.table().equalsIgnoreCase(name)
                        || s.alias() != null && s.alias().equalsIgnoreCase(name);
            for (PlanNode child : node.children()) if (hasTable(child, name)) return true;
            return false;
        }

        String findTable(PlanNode node, String name)
                throws OptimizerInternalException.CostEstimationException {
            if (node instanceof TableScanNode s
                    && (name == null
                            || s.table().equalsIgnoreCase(name)
                            || s.alias() != null && s.alias().equalsIgnoreCase(name)))
                return s.table();
            if (node instanceof IndexScanNode s
                    && (name == null
                            || s.table().equalsIgnoreCase(name)
                            || s.alias() != null && s.alias().equalsIgnoreCase(name)))
                return s.table();
            for (PlanNode child : node.children()) {
                try {
                    return findTable(child, name);
                } catch (OptimizerInternalException.CostEstimationException ignored) {
                }
            }
            throw fail("Cannot resolve table " + name + " in " + node.getClass().getName());
        }

        PlanNode relationNode(PlanNode node, String table)
                throws OptimizerInternalException.CostEstimationException {
            if (node instanceof TableScanNode scan && scan.table().equalsIgnoreCase(table))
                return node;
            if (node instanceof IndexScanNode scan && scan.table().equalsIgnoreCase(table))
                return node;
            if (node instanceof SelectionPlanNode selection && hasTable(selection.child(), table))
                return node;
            for (PlanNode child : node.children()) {
                if (hasTable(child, table)) return relationNode(child, table);
            }
            throw fail("Cannot find relation for table " + table);
        }

        String firstTable(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            if (node instanceof TableScanNode s) return s.table();
            if (node instanceof IndexScanNode s) return s.table();
            for (PlanNode child : node.children()) {
                try {
                    return firstTable(child);
                } catch (OptimizerInternalException.CostEstimationException ignored) {
                }
            }
            throw fail("Cannot resolve column table for " + node.getClass().getName());
        }

        long joined(long l, long r, Expr condition, PlanNode scope)
                throws OptimizerInternalException.CostEstimationException {
            long product = safeMultiply(l, r);
            if (condition == null) return product;
            return Math.max(
                    product == 0 ? 0 : 1,
                    Math.min(product, ceil(product * selectivity(condition, scope))));
        }

        long selected(long n, double sel) {
            return n == 0 ? 0 : Math.max(1, Math.min(n, ceil(n * sel)));
        }

        long packed(long n, double size) {
            if (n == 0) return 0;
            double f = Math.max(1, Math.floor(parameters.blockSizeBytes() / Math.max(1, size)));
            return ceil(n / f);
        }

        double factor(PlanNode node) throws OptimizerInternalException.CostEstimationException {
            if (node instanceof TableScanNode scan) {
                double factor = stats(scan.table()).blockingFactor();
                if (factor > 0) return factor;
            }
            if (node instanceof IndexScanNode scan) {
                double factor = stats(scan.table()).blockingFactor();
                if (factor > 0) return factor;
            }
            return Math.max(1, Math.floor(parameters.blockSizeBytes() / Math.max(1, size(node))));
        }

        double recordSize(String table) throws OptimizerInternalException.CostEstimationException {
            TableStats t = stats(table);
            if (t.averageRecordSize() > 0) return t.averageRecordSize();
            return catalog.getSchema(table).columns().stream()
                    .mapToDouble(c -> columnSize(c))
                    .sum();
        }

        double columnSize(String table, String name)
                throws OptimizerInternalException.CostEstimationException {
            Column c =
                    catalog.getSchema(table).columns().stream()
                            .filter(x -> x.name().equalsIgnoreCase(name))
                            .findFirst()
                            .orElseThrow(() -> fail("Unknown column " + table + "." + name));
            return columnSize(c);
        }

        double columnSize(Column c) {
            return c.type().isNumeric() ? 4 : c.length();
        }
    }

    private static long safeMultiply(long a, long b) {
        if (a == 0 || b == 0) return 0;
        return a > LIMIT / b ? LIMIT : a * b;
    }

    private static long ceil(double n) {
        if (!Double.isFinite(n) || n >= LIMIT) return LIMIT;
        return Math.min(LIMIT, (long) Math.ceil(n));
    }

    private static Cost cost(double seeks, double transfers) {
        return new Cost(clamp(seeks), clamp(transfers));
    }

    private static Cost add(Cost left, Cost right) {
        return cost(
                (double) left.getSeeks() + right.getSeeks(),
                (double) left.getTransfers() + right.getTransfers());
    }

    private static long clamp(double value) {
        if (!Double.isFinite(value) || value >= LIMIT) return LIMIT;
        return Math.max(0, (long) Math.ceil(value));
    }

    private static OptimizerInternalException.CostEstimationException fail(String message) {
        return new OptimizerInternalException.CostEstimationException(message);
    }
}
