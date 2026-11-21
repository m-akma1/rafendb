package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.*;
import static org.rafendb.optimizer.join.JoinAccessPathTestSupport.*;

import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.plan.*;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.optimizer.QueryOptimizerImpl;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;
import org.rafendb.optimizer.cost.Cost;
import org.rafendb.optimizer.cost.CostModel;
import org.rafendb.optimizer.cost.StatsCostModel;
import org.rafendb.optimizer.parser.QueryParser;
import org.rafendb.optimizer.plan.InitialPlanBuilder;
import org.rafendb.optimizer.rewrite.PlanRewriter;

class QueryPlanSelectionTest {
    private final MockStorageManager storage = new MockStorageManager();
    private final StorageManagerCatalog catalog = new StorageManagerCatalog(storage);
    private final CostModel statistics = new StatsCostModel(catalog);
    private final InitialPlanBuilder initial = new InitialPlanBuilder(statistics);

    @Test
    void choosesCheapestEvaluatedJoinPlan() throws OptimizerInternalException {
        var evaluated = new HashMap<PlanNode, Double>();
        PlanCost costs =
                root -> {
                    int joinSeeks = scanOrder(root).getFirst().equals("p") ? 1 : 8;
                    int scanSeeks = nodes(root, IndexScanNode.class).isEmpty() ? 4 : 1;
                    var cost = new Cost(joinSeeks + scanSeeks, 0);
                    evaluated.put(root, cost.timeMs());
                    return cost;
                };
        var optimizer = pipeline(costs);
        var query =
                (SelectQuery)
                        optimizer.parse(
                                """
                SELECT p.nama, m.nim FROM mahasiswa AS m JOIN prodi AS p
                ON m.kode_prodi = p.kode WHERE m.nim >= 13524276
                """);
        var original = initial.build(query);

        var chosen = optimizer.optimize(query);

        assertTrue(
                evaluated.size() > 1, "Expected multiple evaluated plans, got " + evaluated.size());
        assertCheapest(chosen, evaluated);
        assertEquals(List.of("p", "m"), scanOrder(chosen.root()));
        assertFalse(
                nodes(chosen.root(), IndexScanNode.class).isEmpty(), "Expected the student index");
        assertEquals(rows(storage, original.root()), rows(storage, chosen.root()));
    }

    @Test
    void rewritesSingleTableSelectionAndProjection() throws OptimizerInternalException {
        var optimizer = pipeline(statistics);
        var query =
                (SelectQuery)
                        optimizer.parse(
                                """
                SELECT kode_prodi, nama FROM mahasiswa WHERE ipk > 3.0 AND kode_prodi = 'IF'
                """);
        var original = initial.build(query).root();

        var chosen = optimizer.optimize(query).root();

        assertEquals(1, nodes(original, SelectionPlanNode.class).size(), "Initial conjunction");
        assertEquals(2, nodes(chosen, SelectionPlanNode.class).size(), "Rewritten filters");
        var projection = assertInstanceOf(ProjectionPlanNode.class, chosen);
        assertEquals(query.projection(), projection.columns(), "SELECT column order and bindings");
        assertEquals(rows(storage, original), rows(storage, chosen));
    }

    private QueryOptimizerImpl pipeline(CostModel costs) {
        return new QueryOptimizerImpl(
                new QueryParser(),
                new QueryBinder(catalog),
                new JoinAccessPathOptimizer(storage, costs),
                initial,
                new PlanRewriter(catalog));
    }
}
