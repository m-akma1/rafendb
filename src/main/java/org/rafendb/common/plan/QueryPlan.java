package org.rafendb.common.plan;

import java.util.Objects;

/**
 * An executable query plan produced by the Query Optimizer and consumed by the Query Processor: a
 * tree of {@link PlanNode}s plus the optimizer's estimated total cost.
 *
 * <p>Plans may be produced for SELECT, UPDATE, INSERT, DELETE, transaction commands, and CREATE
 * INDEX; concrete node types reflect the statement family.
 *
 * <p>Immutable and thread-safe.
 *
 * @param root the root node, not null
 * @param totalCost the estimated total cost, in the optimizer's consistent cost unit (QO-08); an
 *     estimate, not a measured cost
 * @see PlanNode
 */
public record QueryPlan(PlanNode root, double totalCost) {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if {@code root} is null
     */
    public QueryPlan {
        Objects.requireNonNull(root, "root");
    }
}
