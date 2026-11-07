package org.rafendb.processor.error;

import org.rafendb.common.error.Component;
import org.rafendb.common.error.RafenDBRuntimeException;

/**
 * Dilempar pas CCM bilang transaksinya udah batal, biar operator berhenti dan pesannya nyampe ke
 * client.
 */
public class TransactionAbortedException extends RafenDBRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Bikin exception dengan pesan.
     *
     * @param message alasan transaksi batal
     */
    public TransactionAbortedException(String message) {
        super(Component.QP, message, null);
    }

    /**
     * Bikin exception dengan pesan dan penyebabnya.
     *
     * @param message alasan transaksi batal
     * @param cause penyebab aslinya
     */
    public TransactionAbortedException(String message, Throwable cause) {
        super(Component.QP, message, cause);
    }
}
