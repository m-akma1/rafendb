package org.rafendb.optimizer.join;

import static org.junit.jupiter.api.Assertions.*;
import static org.rafendb.optimizer.join.JoinAccessPathOptimizer.SearchMode.GENETIC;
import static org.rafendb.optimizer.join.JoinAccessPathTestSupport.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.mock.storage.MockStorageManager;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;
import org.rafendb.optimizer.cost.Cost;
import org.rafendb.optimizer.cost.CostParameters;
import org.rafendb.optimizer.cost.StatsCostModel;
import org.rafendb.optimizer.join.JoinAccessPathOptimizer.SearchConfig;
import org.rafendb.optimizer.join.JoinAccessPathTestSupport.PlanCost;
import org.rafendb.optimizer.parser.QueryParser;
import org.rafendb.optimizer.plan.InitialPlanBuilder;

class GeneticJoinOptimizationTest {
    private final MockStorageManager storage = new MockStorageManager();
    private final StorageManagerCatalog catalog = new StorageManagerCatalog(storage);
    // Small pages and one outer buffer make the intermediate join sizes matter.
    private final StatsCostModel statistics =
            new StatsCostModel(
                    catalog,
                    new CostParameters(Cost.SEEK_MILLIS, Cost.TRANSFER_MILLIS, 128, 3, 0.1, 0.5));
    private final PlanValidator validator =
            new PlanValidator(storage, new AccessPathOptimizer(storage));
    private QueryPlan original;

    @BeforeEach
    void buildFourRelationPlan() throws OptimizerInternalException {
        var parsed =
                new QueryParser()
                        .parse(
                                """
                SELECT m.nama, p.kode, n.nama, r.nim FROM mahasiswa AS r
                JOIN mahasiswa AS n ON n.nim < r.nim
                JOIN prodi AS p ON p.kode = n.kode_prodi
                JOIN mahasiswa AS m ON m.kode_prodi = p.kode
                """);
        var query = (SelectQuery) new QueryBinder(catalog).bind(parsed);
        original = new InitialPlanBuilder(statistics).build(query);
    }

    @Test
    void decodesCrossoverAndMutationAsPermutations() throws CostEstimationException {
        var decoder = new JoinOrderDecoder(original.root(), storage);
        var genes = decoder.originalRelationOrder();
        var aliases = List.of("r", "n", "p", "m");
        var expectedRows = rows(storage, original.root());

        var child = GeneticJoinSearch.orderCrossover(genes, genes.reversed(), 2);
        var mutated = GeneticJoinSearch.swapMutation(child, new Random(42));

        assertEquals(List.of(0, 1, 2, 3), genes, "One gene per relation occurrence");
        assertEquals(List.of(0, 1, 3, 2), child, "Prefix followed by unused donor genes");
        int changedPositions = 0;
        for (int i = 0; i < child.size(); i++) {
            if (!child.get(i).equals(mutated.get(i))) changedPositions++;
        }
        assertEquals(2, changedPositions, "Mutation swaps two distinct positions");

        for (var order : List.of(child, mutated)) {
            assertEquals(genes, order.stream().sorted().toList(), "Complete permutation");
            var decoded = decoder.decode(order);
            assertEquals(order.stream().map(aliases::get).toList(), scanOrder(decoded));
            validator.validate(decoded);
            assertEquals(
                    expectedRows,
                    rows(storage, decoded),
                    "Decoded offsets and SELECT order for " + order);
        }
    }

    @Test
    void acceptsPopulationGenerationAndMutationSettings() throws CostEstimationException {
        var originalOrder = scanOrder(original.root());
        var expectedRelations = originalOrder.stream().sorted().toList();

        // Verify the initial population.
        var singleIndividual = evaluatedOrders(1, 0, 0.0);
        var twoIndividuals = evaluatedOrders(2, 0, 0.0);

        assertEquals(Set.of(originalOrder), singleIndividual);
        assertTrue(twoIndividuals.size() >= 1 && twoIndividuals.size() <= 2);

        // Exercise configurations with and without mutation.
        var withoutMutation = evaluatedOrders(2, 1, 0.0);
        var withMutation = evaluatedOrders(2, 1, 1.0);

        for (var evaluated : List.of(twoIndividuals, withoutMutation, withMutation)) {
            assertTrue(evaluated.contains(originalOrder));

            for (var order : evaluated) {
                assertEquals(expectedRelations, order.stream().sorted().toList());
            }
        }

        // The same seed and configuration should give reproducible results.
        assertEquals(withMutation, evaluatedOrders(2, 1, 1.0));
    }

    @Test
    void comparesGeneticAndHeuristicCosts(TestReporter report) throws CostEstimationException {
        var evaluated = new HashMap<PlanNode, Double>();
        PlanCost fitness =
                root -> {
                    var cost = statistics.estimateTotalCost(root);
                    evaluated.put(root, cost.timeMs());
                    return cost;
                };
        var config = new SearchConfig(GENETIC, 8, 12, 0.5, 42, 1);

        var genetic = new JoinAccessPathOptimizer(storage, fitness, config).optimize(original);
        var heuristic = new JoinAccessPathOptimizer(storage, statistics).optimize(original);

        assertCheapest(genetic, evaluated);
        for (var candidate : evaluated.keySet()) {
            assertEquals(
                    List.of("m", "n", "p", "r"), scanOrder(candidate).stream().sorted().toList());
            validator.validate(candidate);
        }
        var expectedRows = rows(storage, original.root());
        for (var chosen : List.of(genetic, heuristic)) {
            validator.validate(chosen.root());
            assertEquals(expectedRows, rows(storage, chosen.root()));
            assertEquals(
                    statistics.getCost(chosen.root()), chosen.totalCost(), "Shared QO-08 estimate");
            assertTrue(chosen.totalCost() <= original.totalCost(), "Original order was available");
        }
        report.publishEntry(
                Map.of(
                        "genetic_ms",
                        String.valueOf(genetic.totalCost()),
                        "heuristic_ms",
                        String.valueOf(heuristic.totalCost())));
    }

    private Set<List<String>> evaluatedOrders(int population, int generations, double mutation)
            throws CostEstimationException {
        var orders = new HashSet<List<String>>();
        PlanCost fitness =
                root -> {
                    orders.add(scanOrder(root));
                    return statistics.estimateTotalCost(root);
                };
        var config = new SearchConfig(GENETIC, population, generations, mutation, 42, 1);
        new JoinAccessPathOptimizer(storage, fitness, config).optimize(original);
        return orders;
    }
}
