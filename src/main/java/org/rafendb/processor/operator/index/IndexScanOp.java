package org.rafendb.processor.operator.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.Value;
import org.rafendb.common.plan.IndexScanDescriptor;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.error.QueryExecutionException;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;
import org.rafendb.processor.txn.TableLocks;

/**
 * An operator that retrieves table records through an index.
 *
 * <p>The operator first searches the index to obtain matching record identifiers (RIDs).
 * It then fetches the corresponding records from the Storage Manager.
 *
 * <p>The output schema contains all columns of the scanned table, using the table
 * alias as the qualifier when an alias is provided.
 *
 * <p>This operator is not thread-safe. One instance should be used for a single
 * query execution at a time.
 */
public final class IndexScanOp implements Operator {

    private final ExecutionContext context;
    private final StorageManager storage;
    private final String table;
    private final IndexScanDescriptor descriptor;
    private final List<ColumnSchema> schema;

    private List<Rid> rids;
    private int position;

    /**
     * Creates an index scan operator.
     *
     * <p>The table schema is loaded immediately, but the index lookup is performed
     * only when {@link #open()} is called.
     *
     * @param context the execution context
     * @param table the table name
     * @param alias the table alias, or null if no alias is provided
     * @param descriptor the index scan descriptor
     * @throws NullPointerException if context, storage manager, table, or descriptor is null
     */
    public IndexScanOp(
        ExecutionContext context,
        String table,
        String alias,
        IndexScanDescriptor descriptor) {

        this.context = Objects.requireNonNull(context, "context");
        this.storage = Objects.requireNonNull(
            context.getStorageManager(), "storage");
        this.table = Objects.requireNonNull(table, "table");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");

        String qualifier = alias == null ? table : alias;

        TableSchema tableSchema = storage.getSchema(table);

        List<ColumnSchema> columns = new ArrayList<>();

        for (Column column : tableSchema.columns()) {
            Expr.ColumnRef reference =
                new Expr.ColumnRef(qualifier, column.name());

            columns.add(new ColumnSchema(reference, column.type()));
        }

        this.schema = List.copyOf(columns);

        this.rids = null;
        this.position = 0;
    }

    /**
     * Opens the index scan and retrieves matching record identifiers.
     *
     * <p>The previous scan state is discarded before a new lookup is performed.
     * A read lock is acquired before accessing the index.
     *
     * @throws QueryExecutionException if the Storage Manager fails during index lookup
     * @throws IllegalStateException if the session is invalid or has no active transaction
     * @throws org.rafendb.processor.error.TransactionAbortedException
     *         if the transaction is aborted while acquiring the read lock
     */
    @Override
    public void open() {
        close();

        TableLocks.acquireRead(context, table);

        try {
            rids = executeIndexLookup();
            position = 0;
        } catch (StorageInternalException e) {
            rids = null;
            throw new QueryExecutionException(
                "Failed to execute index scan on table " + table, e);
        }
    }

    /**
     * Returns the output schema of this operator.
     *
     * @return an immutable list of column schemas
     */
    @Override
    public List<ColumnSchema> schema() {
        return schema;
    }

    /**
     * Returns the next matching record.
     *
     * <p>Each RID obtained from the index is fetched individually. RIDs that no
     * longer refer to a live record are skipped.
     *
     * @return the next result row, or null if no more records are available
     * @throws IllegalStateException if the operator has not been opened or has been closed
     * @throws QueryExecutionException if fetching a record fails
     */
    @Override
    public ResultRow next() {
        if (rids == null) {
            throw new IllegalStateException("IndexScanOp is not open");
        }

        while (position < rids.size()) {
            Rid rid = rids.get(position++);

            Optional<StoredRow> result;

            try {
                result = storage.fetch(table, rid);
            } catch (StorageInternalException e) {
                throw new QueryExecutionException(
                    "Failed to fetch record from table " + table, e);
            }

            if (result.isEmpty()) {
                continue;
            }

            StoredRow stored = result.get();

            return new ResultRow(
                stored.row().values(),
                stored.rid());
        }

        return null;
    }

    /**
     * Closes the operator and clears its current scan state.
     *
     * <p>This method is safe to call before opening the operator or multiple times.
     */
    @Override
    public void close() {
        rids = null;
        position = 0;
    }

    /**
     * Executes the appropriate index lookup based on the scan condition.
     *
     * <p>Equality conditions use {@code lookupIndex}. Range conditions use
     * {@code rangeIndex}.
     *
     * @return the list of matching record identifiers
     * @throws StorageInternalException if the Storage Manager fails
     * @throws IllegalArgumentException if the index condition is unsupported
     */
    private List<Rid> executeIndexLookup() throws StorageInternalException {

        Expr condition = descriptor.condition();

        if (!(condition instanceof Expr.Binary binary)) {
            throw new IllegalArgumentException(
                "Index condition must be a binary expression");
        }

        if (!(binary.left() instanceof Expr.ColumnRef column)) {
            throw new IllegalArgumentException(
                "Index condition must have a column on the left side");
        }

        if (!(binary.right() instanceof Expr.Literal literal)) {
            throw new IllegalArgumentException(
                "Index condition must have a literal on the right side");
        }

        if (!column.column().equals(descriptor.column())) {
            throw new IllegalArgumentException(
                "Index condition column does not match the index descriptor");
        }

        Value key = literal.value();

        if (key.isNull()) {
            return List.of();
        }

        return switch (binary.op()) {

            case EQ -> storage.lookupIndex(
                descriptor.indexName(),
                key);

            case GT -> storage.rangeIndex(
                descriptor.indexName(),
                key,
                false,
                null,
                false);

            case GE -> storage.rangeIndex(
                descriptor.indexName(),
                key,
                true,
                null,
                false);

            case LT -> storage.rangeIndex(
                descriptor.indexName(),
                null,
                false,
                key,
                false);

            case LE -> storage.rangeIndex(
                descriptor.indexName(),
                null,
                false,
                key,
                true);

            default -> throw new IllegalArgumentException(
                "Unsupported index condition: " + binary.op());
        };
    }
}
