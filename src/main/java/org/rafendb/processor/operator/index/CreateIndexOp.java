package org.rafendb.processor.operator.index;

import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.plan.command.CreateIndexPlanNode;
import org.rafendb.processor.txn.TableLocks;

/**
 * Command used to create an index on a table.
 *
 * <p>CREATE INDEX is not a row-producing operator, therefore this implementation
 * uses ExecutionPlan.CommandAction instead of the Operator interface.
 *
 * <p>The index is built when execute() is called, not when the object is created.
 */
public final class CreateIndexOp implements ExecutionPlan.CommandAction {

    private final ExecutionContext context;
    private final CreateIndexPlanNode node;
    private final StorageManager storage;

    private boolean executed;

    public CreateIndexOp(ExecutionContext context, CreateIndexPlanNode node) {
        this.context = Objects.requireNonNull(context, "context");
        this.node = Objects.requireNonNull(node, "node");
        this.storage = Objects.requireNonNull(context.getStorageManager(), "storage manager");
    }

    /**
     * Executes the CREATE INDEX command exactly once.
     *
     * @return command result with affectedRows = 0
     * @throws IllegalStateException if the command has already been executed
     */
    @Override
    public ExecutionPlan.CommandResult execute() {
        if (executed) {
            throw new IllegalStateException("CREATE INDEX command already executed");
        }

        executed = true;

        TableLocks.acquireWrite(context, node.table());

        IndexInfo indexInfo =
                new IndexInfo(
                        node.name(),
                        node.table(),
                        node.column().column(),
                        toIndexType(node.kind()));

        try {
            storage.createIndex(indexInfo);
        } catch (StorageInternalException e) {
            throw new QueryExecutionException("Failed to create index: " + node.name(), e);
        }

        return new ExecutionPlan.CommandResult(0, "CREATE INDEX");
    }

    /**
     * Converts the index type from the query plan representation
     * to the index type used by the Storage Manager.
     */
    private static IndexType toIndexType(CreateIndexPlanNode.IndexKind kind) {
        return switch (kind) {
            case BTREE -> IndexType.B_PLUS_TREE;
            case HASH -> IndexType.HASH;
        };
    }
}
