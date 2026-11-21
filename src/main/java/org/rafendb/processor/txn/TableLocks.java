package org.rafendb.processor.txn;

import java.util.Objects;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.txn.AccessResult;
import org.rafendb.common.txn.AccessType;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.processor.api.SessionView;
import org.rafendb.processor.error.TransactionAbortedException;

/**
 * Minta lock tabel ke CCM buat transaksi session yang lagi jalan. WAITING ditunggu sebentar terus
 * minta lagi; ABORTED bikin transaksinya di-abort ke CCM terus lempar TransactionAbortedException.
 * State session ga diubah di sini; session yang nyesuaiin pas nangkep exception-nya.
 */
public final class TableLocks {
    private static final long WAIT_MILLIS = 5;

    private TableLocks() {}

    /**
     * Minta lock baca satu tabel, nunggu sampai diizinin.
     *
     * @param session session yang lagi jalanin statement
     * @param table nama tabel
     * @throws IllegalStateException kalau session udah ditutup, ga ada transaksi aktif, atau pas
     *     nunggu session-nya ditutup / thread-nya ke-interrupt
     * @throws TransactionAbortedException kalau CCM bilang transaksinya batal
     */
    public static void acquireRead(SessionView session, String table) {
        acquire(session, AccessType.READ, table);
    }

    /**
     * Minta lock tulis satu tabel, nunggu sampai diizinin.
     *
     * @param session session yang lagi jalanin statement
     * @param table nama tabel
     * @throws IllegalStateException kalau session udah ditutup, ga ada transaksi aktif, atau pas
     *     nunggu session-nya ditutup / thread-nya ke-interrupt
     * @throws TransactionAbortedException kalau CCM bilang transaksinya batal
     */
    public static void acquireWrite(SessionView session, String table) {
        acquire(session, AccessType.WRITE, table);
    }

    /** Polling ke CCM sampai ALLOWED atau ABORTED. Lock-nya per tabel. */
    private static void acquire(SessionView session, AccessType type, String table) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(table, "table");
        TxId tx = session.requireTx();
        ConcurrencyControl ccm = session.ccm();
        ResourceId resource = ResourceId.ofTable(table);
        while (true) {
            AccessResult result = ccm.requestAccess(tx, type, resource);
            switch (result.status()) {
                case ALLOWED -> {
                    return;
                }
                case WAITING -> pause(session);
                case ABORTED -> {
                    TransactionAbortedException failure =
                            new TransactionAbortedException(result.message());
                    abortQuietly(ccm, tx, failure);
                    throw failure;
                }
            }
        }
    }

    /**
     * Abort ke CCM biar lock-nya langsung lepas (abort CCM idempotent, jadi aman kalau session
     * nanti abort lagi). Kalau abort-nya error, error itu ditempel ke exception utama (suppressed).
     */
    private static void abortQuietly(ConcurrencyControl ccm, TxId tx, Exception primary) {
        try {
            ccm.abort(tx);
        } catch (ConcurrencyInternalException | RuntimeException e) {
            primary.addSuppressed(e);
        }
    }

    /** Tidur sebentar sebelum minta lagi, kecuali session-nya udah ditutup. */
    private static void pause(SessionView session) {
        if (!session.isOpen()) {
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
