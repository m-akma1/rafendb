package org.rafendb.processor.error;

import org.rafendb.common.error.ProcessorRuntimeException;

/**
 * Dilempar pas CCM bilang transaksinya udah batal, biar operator berhenti dan pesannya nyampe ke
 * client.
 */
public class TransactionAbortedException extends ProcessorRuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Bikin exception dengan pesan.
     *
     * @param message alasan transaksi batal
     */
    public TransactionAbortedException(String message) {
        super(message);
    }

    /**
     * Bikin exception dengan pesan dan penyebabnya.
     *
     * @param message alasan transaksi batal
     * @param cause penyebab aslinya
     */
    public TransactionAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
