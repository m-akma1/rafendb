package org.rafendb.processor.operator.write;

import java.util.List;
import java.util.Objects;

import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.ExecutionContext;

/**
 * INSERT: adds one row inside the session's current transaction.
 *
 * <p>Order, as required by {@link StorageManager#reserveRid}: WRITE access (CCM), reserve the slot
 * (SM validates the row, nothing changes yet), log the INSERT (CCM to FRM), then store the row.
 * If anything fails after the reservation, the reservation is canceled.
 *
 * <p>Does not begin or commit: the caller guarantees an active transaction. One instance, one
 * execution.
 */
public final class InsertOp implements DMLOperator {
    private final String table;
    private final Row row;
    private final ExecutionContext ctx;
    private final WriteSupport support;

    /** {@code values} are already typed constants in table column order (InsertPlanNode). */
    public InsertOp(String table, List<Value> values, ExecutionContext ctx) {
        this(table, values, ctx, RowImageCodec.DEFAULT);
    }

    public InsertOp(String table, List<Value> values, ExecutionContext ctx, RowImageCodec codec) {
        this.table = Objects.requireNonNull(table, "table");
        this.row = new Row(Objects.requireNonNull(values, "values"));
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.support = new WriteSupport(ctx, codec);
    }

    /** @return 1, the number of inserted rows */
    @Override
    public Long execute() {
        TxId tx = support.requireTx();
        support.acquireWrite(tx, table);

        StorageManager sm = ctx.getStorageManager();
        Rid rid;
        try {
            // StorageRuntimeException (row does not fit the schema) propagates: nothing changed.
            rid = sm.reserveRid(table, row);
        } catch (StorageInternalException e) {
            throw support.storageFailure("reserving a slot in " + table, e);
        }

        long lsn;
        try {
            lsn = support.logInsert(tx, table, rid, row);
        } catch (RuntimeException e) {
            // Nothing was logged (encode failure) or CCM already aborted: only free the slot.
            cancelQuietly(sm, rid);
            throw e;
        }

        try {
            sm.insert(table, rid, row, lsn);
        } catch (StorageInternalException | RuntimeException e) {
            cancelQuietly(sm, rid);
            throw support.abortAfterLoggedFailure(tx, "inserting into " + table, e);
        }
        return 1L;

    }

    private void cancelQuietly(StorageManager sm, Rid rid) {
        try {
            sm.cancelReservation(table, rid);
        } catch (StorageInternalException | RuntimeException ignored) {
            // The original failure is more useful than this one.
        }
    }
}
