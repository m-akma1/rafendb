package org.rafendb.processor.operator.write;

import java.util.Objects;
import java.util.Optional;
import org.rafendb.api.concurrency.ConcurrencyLogging;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.error.TransactionAbortedException;
import org.rafendb.processor.txn.TableLocks;

/**
 * Shared plumbing of {@link InsertOp}, {@link UpdateOp}, and {@link DeleteOp}: find the session's
 * transaction, ask CCM for WRITE access (retrying while it says WAITING), and log a change.
 *
 * <p>It never begins or commits a transaction, and never changes the session's state. It asks CCM
 * to abort in two cases only: CCM itself has already given up on it (the idempotent {@code
 * ccm.abort} makes sure its locks are released), or a change failed to be applied after its record
 * was logged ({@link #abortAfterLoggedFailure}). Either way it throws {@link
 * TransactionAbortedException}, and the session brings its state in line.
 */
final class WriteSupport {
    private final SessionView session;

    WriteSupport(SessionView session) {
        this.session = Objects.requireNonNull(session, "session");
    }

    /** The session's active transaction; DML never creates one (the session does). */
    TxId requireTx() {
        return session.requireTx();
    }

    /**
     * Gets WRITE access to the whole table. CCM protects tables, so one request covers every row
     * of the statement. Asking again with the same arguments is how WAITING is retried.
     * @param tx contains transaction id with type {@code TxId}
     * @param table contains table name with type {@code String}
     */
    void acquireWrite(TxId tx, String table) {
        TableLocks.acquireWrite(session, table);
    }

    // QP3 - logging support example for delete
    long logDelete(TxId tx, String table, Rid rid, Row after) {
        return log(
                tx,
                new ChangeRecord(
                        ResourceId.ofRow(table, rid),
                        Optional.of(session.storage().encodeRow(table, after)),
                        Optional.empty()));
    }

    /** Wraps a storage failure; it is an internal error, not something the user caused. */
    IllegalStateException storageFailure(String what, StorageInternalException cause) {
        return new IllegalStateException("Storage failure while " + what, cause);
    }

    /**
     * Asks CCM to abort, so locks are released early. CCM's abort is idempotent; the session makes
     * sure again when it catches the failure, and retries if this one could not be confirmed.
     */
    private void abortQuietly(TxId tx, Exception primary) {
        try {
            session.ccm().abort(tx);
        } catch (ConcurrencyInternalException | RuntimeException e) {
            primary.addSuppressed(e);
        }
    }

    ChangeRecord insertChange(String table, Rid rid, Row row) {
        return new ChangeRecord(
                ResourceId.ofRow(table, rid),
                Optional.empty(),
                Optional.of(session.storage().encodeRow(table, row)));
    }

    ChangeRecord updateChange(String table, Rid rid, Row before, Row after) {
        return new ChangeRecord(
                ResourceId.ofRow(table, rid),
                Optional.of(session.storage().encodeRow(table, before)),
                Optional.of(session.storage().encodeRow(table, after)));
    }

    ChangeRecord deleteChange(String table, Rid rid, Row before) {
        return new ChangeRecord(
                ResourceId.ofRow(table, rid),
                Optional.of(session.storage().encodeRow(table, before)),
                Optional.empty());
    }

    public long logInsert(TxId tx, String table, Rid rid, Row row) {
        return log(tx, insertChange(table, rid, row));
    }

    /**
     * For a change whose record is already in the WAL but could not be applied. A statement has no
     * rollback of its own, so the whole transaction is aborted.
     *
     * @return the exception to throw; the transaction is already aborted
     */
    TransactionAbortedException abortAfterLoggedFailure(TxId tx, String what, Exception cause) {
        TransactionAbortedException failure =
                new TransactionAbortedException(
                        tx + " was aborted: storage failed while " + what, cause);
        abortQuietly(tx, failure);
        return failure;
    }

    /** Sends one change to CCM, which logs it through FRM, and returns its LSN. */
    long log(TxId tx, ChangeRecord change) {
        if (!(session.ccm() instanceof ConcurrencyLogging logging)) {
            throw new IllegalStateException("CCM does not implement ConcurrencyLogging");
        }
        try {
            return logging.logChange(tx, change);
        } catch (ConcurrencyInternalException e) {
            // CCM already ran its abort path (CC-04); aborting again only confirms it.
            TransactionAbortedException failure =
                    new TransactionAbortedException(
                            tx + " was aborted: its change could not be logged", e);
            abortQuietly(tx, failure);
            throw failure;
        }
    }
}
