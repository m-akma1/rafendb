package org.rafendb.processor.txn;

import java.util.Objects;

import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.txn.TxResult;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.api.SessionContext;
import org.rafendb.processor.error.TransactionAbortedException;

/**
 * Minta lock tabel ke CCM buat transaksi session yang lagi jalan. WAITING ditunggu sebentar terus
 * minta lagi; ABORTED bikin transaksinya di-abort terus lempar TransactionAbortedException.
 */
public final class TableLocks {
    private static final long WAIT_MILLIS = 5;

    private TableLocks() {}

    /**
     * Minta lock baca satu tabel, nunggu sampai diizinin.
     *
     * @param context konteks eksekusi session
     * @param table nama tabel
     * @throws IllegalStateException kalau session ga valid, ga ada transaksi aktif, atau pas nunggu
     *     session-nya ditutup / thread-nya ke-interrupt
     * @throws TransactionAbortedException kalau CCM bilang transaksinya batal
     */
    public static void acquireRead(ExecutionContext context, String table) {
        acquire(context, AccessType.READ, table);
    }

    /**
     * Minta lock tulis satu tabel, nunggu sampai diizinin.
     *
     * @param context konteks eksekusi session
     * @param table nama tabel
     * @throws IllegalStateException kalau session ga valid, ga ada transaksi aktif, atau pas nunggu
     *     session-nya ditutup / thread-nya ke-interrupt
     * @throws TransactionAbortedException kalau CCM bilang transaksinya batal
     */
    public static void acquireWrite(ExecutionContext context, String table) {
        acquire(context, AccessType.WRITE, table);
    }

    /** Polling ke CCM sampai ALLOWED atau ABORTED. Lock-nya per tabel. */
    private static void acquire(ExecutionContext context, AccessType type, String table) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(table, "table");
        TxId tx = requireTx(context.getSContext());
        ConcurrencyControl ccm = context.getConcurrencyControl();
        ResourceId resource = ResourceId.ofTable(table);
        while (true) {
            AccessResult result = ccm.requestAccess(tx, type, resource);
            switch (result.status()) {
                case ALLOWED -> {
                    return;
                }
                case WAITING -> pause(context.getSContext());
                case ABORTED -> {
                    TransactionAbortedException failure =
                            new TransactionAbortedException(result.message());
                    abortAndClear(context, tx, failure);
                    throw failure;
                }
            }
        }
    }

    /** Ambil transaksi aktif session. */
    private static TxId requireTx(SessionContext session) {
        if (!session.isValid()) {
            throw new IllegalStateException("The session is not valid");
        }
        TxId tx = session.getCurrentTxId();
        if (tx == null) {
            throw new IllegalStateException(
                    "No active transaction: run the statement inside AutoCommit.run(...) or after BEGIN");
        }
        return tx;
    }

    /**
     * Abort ke CCM; kalau udah beneran ABORTED, txId session dikosongin. Kalau abort-nya error,
     * error itu ditempel ke exception utama (suppressed).
     */
    private static void abortAndClear(ExecutionContext context, TxId tx, Exception primary) {
        try {
            TxResult result = context.getConcurrencyControl().abort(tx);
            if (result.status() == TxStatus.ABORTED) {
                context.getSContext().setCurrentTxId(null);
            }
        } catch (ConcurrencyInternalException | RuntimeException e) {
            primary.addSuppressed(e);
        }
    }

    /** Tidur sebentar sebelum minta lagi, kecuali session-nya udah ditutup. */
    private static void pause(SessionContext session) {
        if (!session.isValid()) {
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
