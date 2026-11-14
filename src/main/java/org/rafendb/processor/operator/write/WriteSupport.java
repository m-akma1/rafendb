package org.rafendb.processor.operator.write;

import java.util.Objects;
import java.util.Optional;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;

/**
 * Shared plumbing of {@link InsertOp}, {@link UpdateOp}, and {@link DeleteOp}: find the session's
 * transaction, ask CCM for WRITE access (retrying while it says WAITING), and log a change.
 *
 * <p> It never begins or commits a transaction. It aborts one in two cases only: CCM itself has
 * already given up on it (the session state is brought in line through the idempotent {@code
 * ccm.abort}), or a change failed to be applied after its record was logged
 * ({@link #abortAfterLoggedFailure}).
 */
final class WriteSupport {
    private static final long WAIT_MILLIS = 5;

    private final ExecutionContext ctx;
    private final RowImageCodec codec;

    WriteSupport(ExecutionContext ctx, RowImageCodec codec) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** The session's active transaction; DML never creates one (see AutoCommit). */
    TxId requireTx() {
        SessionContext session = ctx.getSContext();
        if (!session.isValid()) {
            throw new IllegalStateException("The session is not valid");
        }
        TxId tx = session.getCurrentTxId();
        if (tx == null) {
            throw new IllegalStateException(
                    "No active transaction: run DML inside AutoCommit.run(...) or after BEGIN");
        }
        return tx;
    }

    /**
     * Gets WRITE access to the whole table. CCM protects tables, so one request covers every row
     * of the statement. Asking again with the same arguments is how WAITING is retried.
     * @param tx contains transaction id with type {@code TxId}
     * @param table contains table name with type {@code String}
     */
    void acquireWrite(TxId tx, String table) {
        ConcurrencyControl ccm = ctx.getConcurrencyControl();
        ResourceId resource = ResourceId.ofTable(table);
        while (true) {
            AccessResult result = ccm.requestAccess(tx, AccessType.WRITE, resource);
            switch (result.status()) {
                case ALLOWED -> {
                    return;
                }
                case WAITING -> pause();
                case ABORTED -> {
                    TransactionAbortedException e =
                            new TransactionAbortedException(result.message());
                    syncAfterAbort(tx, e);
                    throw e;
                }
            }
        }
    }

    long logInsert(TxId tx, String table, Rid rid, Row row) {
        return log(
                tx,
                new ChangeRecord(
                        ResourceId.ofRow(table, rid),
                        Optional.empty(),
                        Optional.of(codec.encode(row))));
    }

    long logUpdate(TxId tx, String table, Rid rid, Row before, Row after) {
        return log(
                tx,
                new ChangeRecord(
                        ResourceId.ofRow(table, rid),
                        Optional.of(codec.encode(before)),
                        Optional.of(codec.encode(after))));
    }

    // QP3 - logging support example for delete
    long logDelete(TxId tx, String table, Rid rid, Row after) {
        return log(
                tx,
                new ChangeRecord(
                        ResourceId.ofRow(table, rid),
                        Optional.of(codec.encode(after)),
                        Optional.empty()));
    }

    /** Wraps a storage failure; it is an internal error, not something the user caused. */
    IllegalStateException storageFailure(String what, StorageInternalException cause) {
        return new IllegalStateException("Storage failure while " + what, cause);
    }

    /**
     * For a change whose record is already in the WAL but could not be applied. A statement has no
     * rollback of its own, so the whole transaction is aborted; otherwise a later COMMIT would
     * make recovery redo a change that never happened.
     *
     * @return the exception to throw; the transaction is already aborted
     */
    TransactionAbortedException abortAfterLoggedFailure(TxId tx, String what, Exception cause) {
        TransactionAbortedException failure =
                new TransactionAbortedException(
                        tx + " was aborted: storage failed while " + what, cause);
        syncAfterAbort(tx, failure);
        return failure;
    }

    private long log(TxId tx, ChangeRecord change) {
        if (!(ctx.getConcurrencyControl() instanceof ConcurrencyLogging logging)) {
            throw new IllegalStateException("CCM does not implement ConcurrencyLogging");
        }
        try {
            return logging.logChange(tx, change);
        } catch (ConcurrencyInternalException e) {
            // CCM already ran its abort path (CC-04); only the session state is left to fix.
            TransactionAbortedException failure =
                    new TransactionAbortedException(
                            tx + " was aborted: its change could not be logged", e);
            syncAfterAbort(tx, failure);
            throw failure;
        }
    }

    /**
     * Clears the session's transaction once CCM confirms ABORTED. If rollback is still incomplete
     * the id is kept so that a later ABORT (or session close) can retry it.
     */
    private void syncAfterAbort(TxId tx, Exception primary) {
        try {
            TxResult result = ctx.getConcurrencyControl().abort(tx);
            if (result.status() == TxStatus.ABORTED) {
                ctx.getSContext().setCurrentTxId(null);
            }
        } catch (ConcurrencyInternalException | RuntimeException e) {
            primary.addSuppressed(e);
        }
    }

    private void pause() {
        if (!ctx.getSContext().isValid()) {
            throw new IllegalStateException("The session was closed while waiting for access");
        }
        try {
            Thread.sleep(WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for access", e);
        }
    }
}
