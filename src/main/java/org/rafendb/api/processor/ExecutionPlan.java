package org.rafendb.api.processor;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.ColumnRef;

/** Proposed translated result; a command is not misrepresented as a row cursor. */
public sealed interface ExecutionPlan {
    /** Ordered headers survive an empty result. Full typed schema remains a shared decision. */
    record Rows(List<ColumnRef> columns, Operator operator) implements ExecutionPlan {
        public Rows {
            columns = List.copyOf(columns);
            Objects.requireNonNull(operator, "operator");
            if (!columns.equals(operator.schema()))
                throw new IllegalArgumentException("Headers differ from schema");
        }
    }

    /** Prepared command, with execution deferred until explicitly requested. */
    record Command(CommandAction action) implements ExecutionPlan {
        public Command {
            Objects.requireNonNull(action, "action");
        }
    }

    /** One execution's action; not thread-safe and must not be invoked twice. */
    interface CommandAction {
        /**
         * Executes once and returns a non-null outcome.
         * @throws IllegalStateException on repeated invocation
         */
        CommandResult execute();
    }

    /** Non-null status and non-negative affected-row count; zero for index creation. */
    record CommandResult(long affectedRows, String status) {
        public CommandResult {
            if (affectedRows < 0) throw new IllegalArgumentException("Negative affected row count");
            Objects.requireNonNull(status, "status");
        }
    }
}
