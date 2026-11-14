package org.rafendb.optimizer.join;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.rafendb.common.error.OptimizerInternalException.CostEstimationException;

/**
 * Searches left-deep join orders using tournament selection, prefix crossover and swap mutation.
 * Fitness is the cheapest access plan evaluated for each order. One elite survives each generation.
 */
final class GeneticJoinSearch {
    private final JoinOrderDecoder decoder;
    private final AccessPathSearch access;
    private final JoinAccessPathOptimizer.SearchConfig config;
    private final Random random;
    private final Map<List<Integer>, AccessPathSearch.Result> fitness = new HashMap<>();

    /** Each search uses its own seeded random generator. */
    GeneticJoinSearch(
            JoinOrderDecoder decoder,
            AccessPathSearch access,
            JoinAccessPathOptimizer.SearchConfig config) {
        this.decoder = decoder;
        this.access = access;
        this.config = config;
        random = new Random(config.seed());
    }

    /** Seeds the population with the source relation order and retains one elite per generation. */
    AccessPathSearch.Result selectBest() throws CostEstimationException {
        List<Individual> population = new ArrayList<>();
        List<Integer> original = decoder.originalRelationOrder();
        population.add(evaluate(original));
        for (int i = 1; i < config.populationSize(); i++) {
            List<Integer> shuffled = new ArrayList<>(original);
            Collections.shuffle(shuffled, random);
            population.add(evaluate(List.copyOf(shuffled)));
        }
        Comparator<Individual> byCost = Comparator.comparingDouble(i -> i.plan().cost());
        for (int generation = 0; generation < config.generations(); generation++) {
            population.sort(byCost);
            List<Individual> next = new ArrayList<>();
            next.add(population.getFirst());
            while (next.size() < config.populationSize()) {
                Individual first = tournament(population);
                Individual second = tournament(population);
                List<Integer> child =
                        orderCrossover(
                                first.order(), second.order(), random.nextInt(original.size() + 1));
                if (random.nextDouble() < config.mutationProbability())
                    child = swapMutation(child, random);
                next.add(evaluate(child));
            }
            population = next;
        }
        population.sort(byCost);
        return population.getFirst().plan();
    }

    /** Samples three individuals with replacement, retaining the first sampled on a cost tie. */
    private Individual tournament(List<Individual> population) {
        Individual best = population.get(random.nextInt(population.size()));
        for (int i = 1; i < 3; i++) {
            Individual candidate = population.get(random.nextInt(population.size()));
            if (candidate.plan().cost() < best.plan().cost()) best = candidate;
        }
        return best;
    }

    /** Caches the bounded access-search result for each relation order. */
    private Individual evaluate(List<Integer> order) throws CostEstimationException {
        AccessPathSearch.Result plan = fitness.get(order);
        if (plan == null) {
            plan = access.selectBestWithinBudget(decoder.decode(order));
            fitness.put(order, plan);
        }
        return new Individual(order, plan);
    }

    /** Keeps first's prefix, then appends second's remaining genes in their original order. */
    static List<Integer> orderCrossover(
            List<Integer> first, List<Integer> second, int prefixLength) {
        if (prefixLength < 0 || prefixLength > first.size() || first.size() != second.size()) {
            throw new IllegalArgumentException("Invalid crossover inputs");
        }
        List<Integer> child = new ArrayList<>(first.subList(0, prefixLength));
        for (int gene : second) if (!child.contains(gene)) child.add(gene);
        return List.copyOf(child);
    }

    /** Swaps two distinct positions in a copy. A single-input order is unchanged. */
    static List<Integer> swapMutation(List<Integer> order, Random random) {
        if (order.size() < 2) return order;
        int first = random.nextInt(order.size());
        int second = random.nextInt(order.size() - 1);
        if (second >= first) second++;
        List<Integer> mutated = new ArrayList<>(order);
        Collections.swap(mutated, first, second);
        return List.copyOf(mutated);
    }

    private record Individual(List<Integer> order, AccessPathSearch.Result plan) {}
}
