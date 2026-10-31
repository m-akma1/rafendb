package org.rafendb.common.txn;

/** Lifecycle state of a transaction. COMMITTED and ABORTED are final. */
public enum TxStatus {
    /** Running; the only state where access can be granted. */
    ACTIVE,
    /** Done executing, waiting for its commit record to be logged. */
    PARTIALLY_COMMITTED,
    /** Cannot continue; keeps its protection on changed data until rollback ends. */
    FAILED,
    /** Rolled back and finished. */
    ABORTED,
    /** Committed and finished. */
    COMMITTED;

    /** Returns {@code true} for {@link #COMMITTED} and {@link #ABORTED}. */
    public boolean isFinal() {
        return this == COMMITTED || this == ABORTED;
    }
}
