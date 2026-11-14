package org.rafendb.optimizer.driver;

import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.error.OptimizerRuntimeException;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.mock.optimizer.catalog.MockCatalog;
import org.rafendb.optimizer.binder.QueryBinder;
import org.rafendb.optimizer.catalog.CachingCatalog;
import org.rafendb.optimizer.catalog.Catalog;
import org.rafendb.optimizer.catalog.StorageManagerCatalog;
import org.rafendb.optimizer.cost.CostModel;
import org.rafendb.optimizer.cost.StatsCostModel;
import org.rafendb.optimizer.join.JoinAccessPathOptimizer;
import org.rafendb.optimizer.parser.QueryParser;
import org.rafendb.optimizer.plan.InitialPlanBuilder;

public final class ScenarioRunner {
    private final QueryParser parser;
    private final QueryBinder binder;
    private final InitialPlanBuilder planBuilder;
    private final JoinAccessPathOptimizer optimizer;

    /**
     * Creates a runner over one mock catalog, shared across every scenario in a run.
     *
     * @param catalog the mock catalog (see {@link MockCatalog})
     * @param costModel the cost model to use, built over the same catalog
     */
    public ScenarioRunner(StorageManager storage) {
        Catalog catalog = new CachingCatalog(new StorageManagerCatalog(storage));
        CostModel costModel = new StatsCostModel(catalog);
        this.parser = new QueryParser();
        this.binder = new QueryBinder(catalog);
        this.planBuilder = new InitialPlanBuilder(costModel);
        this.optimizer = new JoinAccessPathOptimizer(storage, costModel);
    }

    /**
     * Runs one scenario and classifies what happened.
     *
     * @param scenario the scenario to run
     * @return the result, with {@link ScenarioResult#passed()} comparing against {@code
     *     scenario.expected()}
     */
    public ScenarioResult run(TestScenario scenario) {
        try {
            ParsedQuery parsed = parser.parse(scenario.sql()); // may throw SYNTAX_ERROR
            ParsedQuery bound = binder.bind(parsed); // may throw VALIDATION_ERROR
            if (bound instanceof SelectQuery select) {
                QueryPlan plan = planBuilder.build(select);
                QueryPlan optimized = optimizer.optimize(plan);
                // String detail = plan.toString() + "\ncost: " + cost; // tungguin QO-15
                String detail = "cost: " + optimized.totalCost();
                return classify(scenario, ScenarioOutcome.VALID, detail);
            }
            return classify(
                    scenario, ScenarioOutcome.VALID, "Query successfully parsed and validated.");
        } catch (OptimizerRuntimeException e) {
            ScenarioOutcome actual =
                    switch (e.getCode()) {
                        case SYNTAX_ERROR -> ScenarioOutcome.SYNTAX_ERROR;
                        case VALIDATION_ERROR -> ScenarioOutcome.VALIDATION_ERROR;
                        default ->
                                throw new AssertionError(
                                        "Unexpected client error code: " + e.getCode());
                    };
            return classify(scenario, actual, e.getMessage());
        } catch (OptimizerInternalException e) {
            return new ScenarioResult(scenario, null, false, "INTERNAL: " + e.getMessage());
        }
    }

    private static ScenarioResult classify(
            TestScenario scenario, ScenarioOutcome actual, String detail) {
        return new ScenarioResult(scenario, actual, actual == scenario.expected(), detail);
    }
}
