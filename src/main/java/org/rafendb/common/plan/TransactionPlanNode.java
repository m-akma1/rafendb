package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;

/** Local placeholder only: QO supplied the class name but not the command representation. */
public record TransactionPlanNode(Action action) implements PlanNode {
    public TransactionPlanNode {
        Objects.requireNonNull(action, "action");
    }

    public enum Action {
        BEGIN,
        COMMIT,
        ABORT
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "TransactionPlanNode " + action;
    }
}
