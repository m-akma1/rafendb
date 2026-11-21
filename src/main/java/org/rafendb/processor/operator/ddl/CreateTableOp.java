package org.rafendb.processor.operator.ddl;

import java.util.Objects;
import org.rafendb.api.storage.StorageDefinition;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.plan.command.CreateTablePlanNode;

/** CREATE TABLE: registers the schema QO validated. The table is created when executed. */
public final class CreateTableOp implements ExecutionPlan.CommandAction {
    private final CreateTablePlanNode node;
    private final StorageDefinition storageDDL;

    /**
     * Prepares the action; nothing changes until {@link #execute()}.
     *
     * @param context the session, whose {@link SessionView#storageDDL()} is used
     * @param node the table to create
     */
    public CreateTableOp(SessionView context, CreateTablePlanNode node) {
        Objects.requireNonNull(context, "context");
        this.node = Objects.requireNonNull(node, "node");
        this.storageDDL = Objects.requireNonNull(context.storageDDL(), "storageDDL");
    }

    /**
     * Creates the table.
     *
     * @return {@code CREATE TABLE} with zero affected rows
     * @throws QueryExecutionException if SM cannot write the catalog or the table file
     */
    @Override
    public ExecutionPlan.CommandResult execute() {
        try {
            storageDDL.createTable(node.schema());
        } catch (StorageInternalException e) {
            throw new QueryExecutionException(
                    "Failed to create table: " + node.schema().table(), e);
        }
        return new ExecutionPlan.CommandResult(0, "CREATE TABLE");
    }
}
