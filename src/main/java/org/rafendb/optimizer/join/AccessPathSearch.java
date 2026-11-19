package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;
import org.rafendb.common.plan.PlanNode;
import org.rafendb.optimizer.cost.CostModel;

/**
 * Compares complete access plans for a fixed join tree within a configuration budget.
 * Tries combinations breadth first and keeps the first plan on a cost tie.
 */
final class AccessPathSearch {
    private static final int MAX_CACHED_COSTS = 4096;
    private final AccessPathOptimizer access;
    private final PlanValidator validator;
    private final CostModel costs;
    private final int budget;
    private final Map<Object, Double> costCache = new HashMap<>();

    /** Plan nodes and catalog metadata must stay unchanged throughout this query's search. */
    AccessPathSearch(
            AccessPathOptimizer access, PlanValidator validator, CostModel costs, int budget) {
        if (budget < 1) throw new IllegalArgumentException("Access-path budget must be positive");
        this.access = access;
        this.validator = validator;
        this.costs = costs;
        this.budget = budget;
    }

    /** Tries at most budget distinct plans, counting cache hits and keeping the first on ties. */
    Result selectBestWithinBudget(PlanNode arrangement) throws CostEstimationException {
        PlanNode best = arrangement;
        double bestCost = validateAndEstimate(arrangement);
        List<PlanNode> queue = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        seen.add(PlanTrees.structuralKey(arrangement));
        // Start from sequential scans so incoming index choices can be replaced.
        // The incoming plan is evaluated first and counts toward the same budget.
        PlanNode sequential = access.withSequentialScans(arrangement);
        Object sequentialKey = PlanTrees.structuralKey(sequential);
        if (!seen.contains(sequentialKey)) {
            if (seen.size() == budget) return new Result(best, bestCost, seen.size(), false);
            seen.add(sequentialKey);
            double cost = validateAndEstimate(sequential);
            if (cost < bestCost) {
                best = sequential;
                bestCost = cost;
            }
        }
        queue.add(sequential);
        // Keep expensive intermediate plans, since another index can make the combination cheaper.
        for (int position = 0; position < queue.size(); position++) {
            for (PlanNode candidate : access.generateCandidates(queue.get(position))) {
                Object key = PlanTrees.structuralKey(candidate);
                if (seen.contains(key)) continue;
                if (seen.size() == budget) return new Result(best, bestCost, seen.size(), false);
                seen.add(key);
                double cost = validateAndEstimate(candidate);
                if (cost < bestCost) {
                    best = candidate;
                    bestCost = cost;
                }
                queue.add(candidate);
            }
        }
        return new Result(best, bestCost, seen.size(), true);
    }

    /** A full cache causes recomputation without changing which plan can win. */
    private double validateAndEstimate(PlanNode root) throws CostEstimationException {
        Object key = PlanTrees.structuralKey(root);
        Double saved = costCache.get(key);
        // A cached plan has already passed validation against this query's metadata.
        if (saved != null) return saved;
        validator.validate(root);
        double cost = costs.estimateTotalCost(root).timeMs();
        if (costCache.size() < MAX_CACHED_COSTS) costCache.put(key, cost);
        return cost;
    }

    /**
     * Cost is in milliseconds. evaluatedConfigurations counts distinct plans, including cache hits.
     * Exhaustive covers access choices for this arrangement only.
     */
    record Result(PlanNode root, double cost, int evaluatedConfigurations, boolean exhaustive) {}
}
