package org.rafendb.optimizer.cost;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.CostEstimationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.plan.CartesianPlanNode;
import org.rafendb.common.plan.IndexScanNode;
import org.rafendb.common.plan.JoinAlgorithm;
import org.rafendb.common.plan.JoinPlanNode;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.SelectionPlanNode;
import org.rafendb.common.plan.SortPlanNode;
import org.rafendb.common.plan.TableScanNode;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.optimizer.catalog.MockCatalog;
import org.rafendb.optimizer.catalog.CachingCatalog;
import org.rafendb.optimizer.catalog.Catalog;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;

class StatsCostModelTest {
    @Test
    void usesTableStatisticsAndSelectionSelectivity() {
        StatsCostModel model = new StatsCostModel(MockCatalog.defaults());
        TableScanNode scan = new TableScanNode(null, "mahasiswa");
        assertEquals(100, model.estimateRows(scan));
        assertEquals(new Cost(1, 1), model.estimateLocalCost(scan));
        Expr eq =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("mahasiswa", "nim"),
                        new Expr.Literal(org.rafendb.common.type.Value.ofInt(7)));
        assertEquals(1, model.estimateRows(new SelectionPlanNode(eq, scan)));

        Expr range =
                new Expr.Binary(
                        Expr.Op.GT,
                        new Expr.ColumnRef("mahasiswa", "nim"),
                        new Expr.Literal(Value.ofInt(50)));
        assertEquals(34, model.estimateRows(new SelectionPlanNode(range, scan)));
        Expr ne =
                new Expr.Binary(
                        Expr.Op.NE,
                        new Expr.ColumnRef("mahasiswa", "nim"),
                        new Expr.Literal(Value.ofInt(7)));
        assertEquals(99, model.estimateRows(new SelectionPlanNode(ne, scan)));
    }

    @Test
    void joinAndPushedSelectionCostsAreFinite() {
        StatsCostModel model = new StatsCostModel(MockCatalog.defaults());
        TableScanNode a = new TableScanNode(null, "mahasiswa");
        TableScanNode b = new TableScanNode(null, "prodi");
        Expr cond =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("mahasiswa", "kode_prodi"),
                        new Expr.ColumnRef("prodi", "kode"));
        JoinPlanNode join = new JoinPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, cond, a, b);
        assertTrue(model.estimateRows(join) >= 1);
        assertTrue(model.estimateTotalCost(join).getTransfers() > 0);
        Expr filter =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("mahasiswa", "nim"),
                        new Expr.Literal(org.rafendb.common.type.Value.ofInt(1)));
        var pushed =
                new JoinPlanNode(
                        JoinAlgorithm.BLOCK_NESTED_LOOP, cond, new SelectionPlanNode(filter, a), b);
        assertTrue(
                model.estimateTotalCost(pushed).timeMs() <= model.estimateTotalCost(join).timeMs());
    }

    @Test
    void pushedDownSelectionIsNoMoreExpensiveForBothNestedLoopAlgorithms() {
        MockCatalog catalog =
                MockCatalog.builder()
                        .table("a")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(100_000, 1_000, 4, 1_024, Map.of("k", 100L))
                        .done()
                        .table("b")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(100_000, 1_000, 4, 1_024, Map.of("k", 100L))
                        .done()
                        .build();
        StatsCostModel model = new StatsCostModel(catalog);
        TableScanNode a = new TableScanNode(null, "a");
        TableScanNode b = new TableScanNode(null, "b");
        Expr joinCondition =
                new Expr.Binary(
                        Expr.Op.EQ, new Expr.ColumnRef("a", "k"), new Expr.ColumnRef("b", "k"));
        Expr filter =
                new Expr.Binary(
                        Expr.Op.EQ, new Expr.ColumnRef("a", "k"), new Expr.Literal(Value.ofInt(1)));
        for (JoinAlgorithm algorithm :
                List.of(JoinAlgorithm.BLOCK_NESTED_LOOP, JoinAlgorithm.NESTED_LOOP)) {
            JoinPlanNode original = new JoinPlanNode(algorithm, joinCondition, a, b);
            JoinPlanNode pushed =
                    new JoinPlanNode(algorithm, joinCondition, new SelectionPlanNode(filter, a), b);
            assertTrue(
                    model.estimateTotalCost(pushed).timeMs()
                            <= model.estimateTotalCost(original).timeMs());
        }
    }

    @Test
    void cacheLoadsStatisticsOnceAndUnknownNodesFailClearly() throws Exception {
        MockCatalog base = MockCatalog.defaults();
        AtomicInteger calls = new AtomicInteger();
        Catalog counting =
                new Catalog() {
                    @Override
                    public List<String> listTables() {
                        return base.listTables();
                    }

                    @Override
                    public TableSchema getSchema(String table) {
                        return base.getSchema(table);
                    }

                    @Override
                    public TableStats getStats(String table) {
                        calls.incrementAndGet();
                        return base.getStats(table);
                    }

                    @Override
                    public List<IndexInfo> listIndexes(String table) {
                        return base.listIndexes(table);
                    }
                };
        CachingCatalog cache = new CachingCatalog(counting);
        cache.getStats("mahasiswa");
        cache.getStats("mahasiswa");
        assertEquals(1, calls.get());
        PlanNode unknown =
                new PlanNode() {
                    @Override
                    public List<PlanNode> children() {
                        return List.of();
                    }

                    @Override
                    public String describe() {
                        return "unknown";
                    }
                };
        assertTrue(
                assertThrows(
                                CostEstimationException.class,
                                () -> new StatsCostModel(base).estimateRows(unknown))
                        .getMessage()
                        .contains(unknown.getClass().getName()));
    }

    @Test
    void estimatesCartesianEmptySortAndParallelCalls() {
        MockCatalog catalog =
                MockCatalog.builder()
                        .table("empty")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(0, 0, 0, 0, Map.of("k", 0L))
                        .done()
                        .table("large")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(10_000, 100, 4, 100, Map.of("k", 10_000L))
                        .done()
                        .build();
        StatsCostModel model = new StatsCostModel(catalog);
        TableScanNode empty = new TableScanNode(null, "empty");
        assertEquals(0, model.estimateRows(empty));
        assertEquals(new Cost(0, 0), model.estimateLocalCost(empty));
        assertEquals(0, model.estimateRows(new SelectionPlanNode(null, empty)));

        TableScanNode small = new TableScanNode(null, "empty");
        TableScanNode large = new TableScanNode(null, "large");
        assertEquals(
                0,
                model.estimateRows(
                        new org.rafendb.common.plan.CartesianPlanNode(
                                JoinAlgorithm.BLOCK_NESTED_LOOP, small, large)));
        SortPlanNode inMemory = new SortPlanNode(small, new Expr.ColumnRef("empty", "k"));
        assertEquals(new Cost(0, 0), model.estimateLocalCost(inMemory));
        SortPlanNode external = new SortPlanNode(large, new Expr.ColumnRef("large", "k"));
        assertTrue(model.estimateLocalCost(external).getTransfers() > 0);

        Cost expected = model.estimateTotalCost(large);
        assertEquals(
                expected,
                java.util.concurrent.CompletableFuture.supplyAsync(
                                () -> model.estimateTotalCost(large))
                        .join());
    }

    @Test
    void estimatesJoinAlgorithmsAndAvailableIndexMetadata() {
        StatsCostModel model = new StatsCostModel(MockCatalog.defaults());
        TableScanNode outer = new TableScanNode(null, "mahasiswa");
        TableScanNode inner = new TableScanNode(null, "prodi");
        Expr condition =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("mahasiswa", "kode_prodi"),
                        new Expr.ColumnRef("prodi", "kode"));
        assertEquals(
                800,
                model.estimateRows(new CartesianPlanNode(JoinAlgorithm.HASH_JOIN, outer, inner)));
        assertEquals(
                new Cost(0, 0),
                model.estimateLocalCost(
                        new JoinPlanNode(JoinAlgorithm.HASH_JOIN, condition, outer, inner)));
        assertEquals(
                new Cost(0, 0),
                model.estimateLocalCost(
                        new JoinPlanNode(JoinAlgorithm.SORT_MERGE, condition, outer, inner)));
        assertEquals(
                new Cost(198, 99),
                model.estimateLocalCost(
                        new JoinPlanNode(JoinAlgorithm.NESTED_LOOP, condition, outer, inner)));
        assertEquals(new Cost(2, 2), model.estimateLocalCost(new IndexScanNode(null, "mahasiswa")));
        assertEquals(new Cost(2, 2), model.estimateLocalCost(new IndexScanNode(null, "prodi")));

        TableScanNode shared = new TableScanNode(null, "mahasiswa");
        CartesianPlanNode dag =
                new CartesianPlanNode(JoinAlgorithm.BLOCK_NESTED_LOOP, shared, shared);
        assertEquals(new Cost(1, 1), model.estimateTotalCost(dag));
    }

    @Test
    void storageManagerCatalogForwardsPublicMetadataCalls() throws Exception {
        MockCatalog fixture = MockCatalog.defaults();
        StorageManager storage =
                (StorageManager)
                        java.lang.reflect.Proxy.newProxyInstance(
                                StorageManager.class.getClassLoader(),
                                new Class<?>[] {StorageManager.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "listTables" -> fixture.listTables();
                                            case "getSchema" -> fixture.getSchema((String) args[0]);
                                            case "getStats" -> fixture.getStats((String) args[0]);
                                            case "listIndexes" ->
                                                    fixture.listIndexes((String) args[0]);
                                            default ->
                                                    throw new UnsupportedOperationException(
                                                            method.getName());
                                        });
        Catalog catalog = new StorageManagerCatalog(storage);
        assertEquals(fixture.listTables(), catalog.listTables());
        assertEquals(fixture.getSchema("mahasiswa"), catalog.getSchema("mahasiswa"));
        assertEquals(fixture.getStats("mahasiswa"), catalog.getStats("mahasiswa"));
        assertEquals(fixture.listIndexes("mahasiswa"), catalog.listIndexes("mahasiswa"));
    }

    @Test
    void handlesMissingZeroAndStaleDistinctCounts() {
        MockCatalog catalog =
                MockCatalog.builder()
                        .table("missing_v")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(10, 1, 4, 1024, Map.of())
                        .done()
                        .table("zero_v")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(10, 1, 4, 1024, Map.of("k", 0L))
                        .done()
                        .table("stale_v")
                        .columns(new Column("k", DataType.INTEGER, 0, false, false))
                        .stats(10, 1, 4, 1024, Map.of("k", 1_000L))
                        .done()
                        .build();
        StatsCostModel model = new StatsCostModel(catalog);
        for (String table : List.of("missing_v", "zero_v")) {
            Expr equality =
                    new Expr.Binary(
                            Expr.Op.EQ,
                            new Expr.ColumnRef(table, "k"),
                            new Expr.Literal(Value.ofInt(1)));
            assertEquals(
                    10,
                    model.estimateRows(
                            new SelectionPlanNode(equality, new TableScanNode(null, table))));
        }
        Expr staleEquality =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef("stale_v", "k"),
                        new Expr.Literal(Value.ofInt(1)));
        assertEquals(
                1,
                model.estimateRows(
                        new SelectionPlanNode(staleEquality, new TableScanNode(null, "stale_v"))));
    }
}
