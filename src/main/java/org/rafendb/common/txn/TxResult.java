package org.rafendb.common.txn;

import java.util.Objects;

/**
 * Outcome of a commit or abort request. {@code status} is the transaction state after the call: a
 * commit succeeded when it is {@link TxStatus#COMMITTED}, an abort when it is {@link
 * TxStatus#ABORTED}. Anything else is a rejection and {@code message} says why.
 *
 * @param status the transaction state after the call
 * @param message why the request was rejected; empty on success
 */
public record TxResult(TxStatus status, String message) {
    /** @throws NullPointerException if an argument is null */
    public TxResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }
}
