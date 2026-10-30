package org.rafendb.mock.processor;

import java.util.List;
import java.util.Objects;
import org.rafendb.api.processor.ExecutionPlan;
import org.rafendb.api.processor.PlanTranslator;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.plan.*;

/**
 * Thread-safe dummy translator. Row plans return configured headers and no rows;
 * write plans return zero affected rows and an explicit MOCK status. No plan is executed.
 */
public final class MockPlanTranslator implements PlanTranslator {
    private final List<ColumnRef> columns;

    /** Copies ordered, non-null dummy headers. Caller supplies the intended result headers. */
    public MockPlanTranslator(List<ColumnRef> columns) {
        this.columns = List.copyOf(columns);
    }

    /** {@inheritDoc} This mock checks null, but does not validate plan semantics. */
    @Override
    public ExecutionPlan translate(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.getRoot() instanceof InsertPlanNode
                || plan.getRoot() instanceof UpdatePlanNode
                || plan.getRoot() instanceof DeletePlanNode
                || plan.getRoot() instanceof CreateIndexPlanNode
                || plan.getRoot() instanceof TransactionPlanNode) {
            return new ExecutionPlan.Command(new DummyCommand());
        }
        return new ExecutionPlan.Rows(columns, new MockOperator(columns, List.of()));
    }

    private static final class DummyCommand implements ExecutionPlan.CommandAction {
        private boolean executed;

        @Override
        public ExecutionPlan.CommandResult execute() {
            if (executed) throw new IllegalStateException("Command already executed");
            executed = true;
            return new ExecutionPlan.CommandResult(0, "MOCK: no storage changes");
        }
    }
}
