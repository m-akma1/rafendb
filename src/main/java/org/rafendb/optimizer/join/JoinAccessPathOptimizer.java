package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.optimizer.cost.CostModel;

/**
 * Chooses the cheapest evaluated join and access plan.
 * Cost-based search accepts read plans with operator-local column bindings.
 * Each call keeps its own search state. Concurrent calls need thread-safe storage and cost providers.
 */
public final class JoinAccessPathOptimizer {
    private static final int BEAM_WIDTH = 8;
    private static final int MAX_ROUNDS = 6;
    private static final int MAX_CANDIDATES = 128;

    private final JoinOptimizer joins = new JoinOptimizer();
    private final StorageManager storage;
    private final AccessPathOptimizer access;
    private final CostModel costs;
    private final PlanValidator validator;
    private final SearchConfig config;

    /** Normalizes joins without a catalog or cost model. */
    public JoinAccessPathOptimizer() {
        storage = null;
        access = null;
        costs = null;
        validator = null;
        config = SearchConfig.defaults();
    }

    /** Normalizes joins using catalog information, without estimating costs. */
    public JoinAccessPathOptimizer(StorageManager storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
        access = null;
        costs = null;
        validator = null;
        config = SearchConfig.defaults();
    }

    /** Creates a beam-search optimizer using the supplied catalog and cost model. */
    public JoinAccessPathOptimizer(StorageManager storage, CostModel costs) {
        this(storage, costs, SearchConfig.defaults());
    }

    /** Uses the requested search mode and settings. */
    public JoinAccessPathOptimizer(StorageManager storage, CostModel costs, SearchConfig config) {
        this.storage = Objects.requireNonNull(storage, "storage");
        access = new AccessPathOptimizer(storage);
        this.costs = Objects.requireNonNull(costs, "costs");
        this.config = Objects.requireNonNull(config, "config");
        validator = new PlanValidator(storage, access);
    }

    /**
     * Normalizes joins, then compares complete join and access plans, including plans without joins.
     * Beam search explores bounded swaps and regroupings. GA explores left-deep permutations
     * and also compares the normalized original plan. Each arrangement has a bounded access search.
     * Neither search guarantees a global optimum. Ties keep discovery order. GA wins its final tie.
     * Without a cost model, only normalization runs and the supplied cost is preserved.
     *
     * @param plan the input query plan, not null
     * @return the selected plan with its complete estimated cost, or the original if unchanged
     * @throws NullPointerException if plan is null
     * @throws CostEstimationException if bindings or operators are unsupported, or an estimate fails
     */
    public QueryPlan optimize(QueryPlan plan) throws CostEstimationException {
        Objects.requireNonNull(plan, "plan");
        if (costs != null) validator.validate(plan.root());
        PlanNode normalized = joins.normalize(plan.root(), storage);
        if (costs == null) {
            return normalized == plan.root() ? plan : new QueryPlan(normalized, plan.totalCost());
        }

        AccessPathSearch accessSearch =
                new AccessPathSearch(access, validator, costs, config.accessPathBudget());
        AccessPathSearch.Result best =
                config.mode() == SearchMode.HEURISTIC
                        ? selectHeuristicPlan(normalized, accessSearch)
                        : selectGeneticPlan(normalized, accessSearch);
        return best.root() == plan.root() && best.cost() == plan.totalCost()
                ? plan
                : new QueryPlan(best.root(), best.cost());
    }

    private AccessPathSearch.Result selectGeneticPlan(
            PlanNode normalized, AccessPathSearch accessSearch) throws CostEstimationException {
        AccessPathSearch.Result genetic =
                new GeneticJoinSearch(
                                new JoinOrderDecoder(normalized, storage), accessSearch, config)
                        .selectBest();
        // GA builds left-deep trees, so also compare the original plan.
        AccessPathSearch.Result originalPhysical = accessSearch.selectBestWithinBudget(normalized);
        return originalPhysical.cost() < genetic.cost() ? originalPhysical : genetic;
    }

    private AccessPathSearch.Result selectHeuristicPlan(
            PlanNode normalized, AccessPathSearch accessSearch) throws CostEstimationException {
        Candidate best = new Candidate(normalized, accessSearch.selectBestWithinBudget(normalized));
        List<Candidate> frontier = List.of(best);
        Set<Object> seen = new HashSet<>();
        seen.add(PlanTrees.structuralKey(normalized));
        for (int round = 0; round < MAX_ROUNDS && !frontier.isEmpty(); round++) {
            List<Candidate> next = new ArrayList<>();
            for (Candidate current : frontier) {
                List<PlanNode> alternatives =
                        joins.generateCandidates(current.arrangement(), storage);
                for (PlanNode alternative : alternatives) {
                    if (seen.size() >= MAX_CANDIDATES) break;
                    if (!seen.add(PlanTrees.structuralKey(alternative))) continue;
                    Candidate candidate =
                            new Candidate(
                                    alternative, accessSearch.selectBestWithinBudget(alternative));
                    next.add(candidate);
                    if (candidate.access().cost() < best.access().cost()) best = candidate;
                }
                if (seen.size() >= MAX_CANDIDATES) break;
            }
            next.sort(Comparator.comparingDouble(c -> c.access().cost()));
            frontier = next.subList(0, Math.min(BEAM_WIDTH, next.size()));
            if (seen.size() >= MAX_CANDIDATES) break;
        }
        return best.access();
    }

    /** Later join changes use the arrangement before access choices. */
    private record Candidate(PlanNode arrangement, AccessPathSearch.Result access) {}

    /** Join-order search strategy. */
    public enum SearchMode {
        HEURISTIC,
        GENETIC
    }

    /**
     * Bounded search settings. Population, generations and mutation apply to GENETIC only.
     * The access budget applies per arrangement in either mode. Each call uses its own seeded randomness.
     *
     * @param mode requested algorithm
     * @param populationSize individuals, 1..128
     * @param generations replacement rounds, 0..200
     * @param mutationProbability swap probability, finite in [0,1]
     * @param seed reproducible random seed
     * @param accessPathBudget complete access configurations per arrangement, 1..256
     */
    public record SearchConfig(
            SearchMode mode,
            int populationSize,
            int generations,
            double mutationProbability,
            long seed,
            int accessPathBudget) {
        public SearchConfig {
            Objects.requireNonNull(mode, "mode");
            if (populationSize < 1
                    || populationSize > 128
                    || generations < 0
                    || generations > 200
                    || !Double.isFinite(mutationProbability)
                    || mutationProbability < 0
                    || mutationProbability > 1
                    || accessPathBudget < 1
                    || accessPathBudget > 256)
                throw new IllegalArgumentException("Invalid QO5 search configuration");
        }

        /** Uses beam search by default. */
        public static SearchConfig defaults() {
            return new SearchConfig(SearchMode.HEURISTIC, 16, 20, 0.2, 0L, 64);
        }
    }
}
