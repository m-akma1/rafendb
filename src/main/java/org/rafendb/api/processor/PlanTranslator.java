package org.rafendb.api.processor;

import org.rafendb.common.plan.QueryPlan;

/**
 * Proposed translator boundary. Implementations must permit concurrent calls, returning
 * independent executions. Milestone 1 supplies this contract and a dummy implementation;
 * it does not require SQL execution or wiring real worker operators.
 * Real execution context injection is pending QP1/QP5 agreement.
 */
public interface PlanTranslator {
    /**
     * Prepares a non-null supported plan without opening cursors or mutating storage.
     * @param plan non-null query plan
     * @return non-null prepared row or command execution
     * @throws NullPointerException if plan is null
     * @throws IllegalArgumentException if the plan is invalid or unsupported
     */
    ExecutionPlan translate(QueryPlan plan);
}
