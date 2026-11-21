package org.rafendb.processor.operator.ddl;

import java.util.Objects;
import org.rafendb.api.storage.StorageDefinition;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.plan.command.DropTablePlanNode;
import org.rafendb.processor.txn.TableLocks;

/**
 * DROP TABLE: removes the table, its rows and its indexes when executed. With CASCADE, SM also
 * removes the other tables' foreign keys to it; those tables and their rows stay, so only this
 * table is locked.
 */
public final class DropTableOp implements ExecutionPlan.CommandAction {
    private final SessionView context;
    private final DropTablePlanNode node;
    private final StorageDefinition storageDDL;

    /**
     * Prepares the action; nothing is locked or changed until {@link #execute()}.
     *
     * @param context the session, whose {@link SessionView#storageDDL()} is used
     * @param node the table to drop and whether to cascade
     */
    public DropTableOp(SessionView context, DropTablePlanNode node) {
        this.context = Objects.requireNonNull(context, "context");
        this.node = Objects.requireNonNull(node, "node");
        this.storageDDL = Objects.requireNonNull(context.storageDDL(), "storageDDL");
    }

    /**
     * Takes a WRITE lock on the table, so no other transaction is reading or writing it, then drops
     * it.
     *
     * @return {@code DROP TABLE} with zero affected rows
     * @throws QueryExecutionException if SM cannot write the catalog or remove the table files
     */
    @Override
    public ExecutionPlan.CommandResult execute() {
        TableLocks.acquireWrite(context, node.table());
        try {
            storageDDL.dropTable(node.table(), node.cascade());
        } catch (StorageInternalException e) {
            throw new QueryExecutionException("Failed to drop table: " + node.table(), e);
        }
        return new ExecutionPlan.CommandResult(0, "DROP TABLE");
    }
}
