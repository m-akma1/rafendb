package org.rafendb.api.recovery;

import java.io.IOException;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.TxId;

/**
 * Thread-safe FRM contract consumed by CCM (CC-03 to CC-05). Arguments are non-null; null throws
 * NullPointerException. CCM serializes calls per transaction; recover runs exclusively at startup.
 * Successful log calls below are synchronous/durable; QP applies changes only after acknowledgment.
 * FRM implementations must conform to these shared types: preserve long transaction IDs and
 * complete row identities without narrowing. LSNs come from one global increasing sequence.
 */
public interface RecoveryService {

    /**
     * Persists BEGIN with its ID and logical begin timestamp before exposing the transaction.
     *
     * @param tx the new transaction ID
     * @param timestamp its positive begin timestamp
     * @throws IllegalArgumentException if timestamp is not positive
     * @throws IOException if logging fails
     */
    void logBegin(TxId tx, long timestamp) throws IOException;

    /**
     * Persists exactly one change; never called for reads or WAITING polls. Caller keeps image
     * bytes unchanged until return; FRM must not retain mutable caller buffers afterward.
     *
     * @param tx the transaction owning the change
     * @param change the physical change, forwarded unchanged by CCM
     * @return this change record's actual persistent LSN for the SM write, not a success flag
     * @throws IllegalArgumentException if the change has invalid row identity or images
     * @throws IOException if logging fails; CCM must stop the write and enter its abort path
     */
    long logChange(TxId tx, ChangeRecord change) throws IOException;

    /**
     * Persists COMMIT before CCM reports success or releases protection. On failure, abort only
     * if no COMMIT is durable; an uncertain outcome requires recovery, not blind rollback.
     *
     * @param tx the transaction whose operations have drained
     * @throws IOException if durable commit cannot be confirmed
     */
    void commit(TxId tx) throws IOException;

    /**
     * Undoes changes at their original row identities and records ABORT before returning success.
     * Idempotent after successful rollback; committed transactions cannot be rolled back.
     *
     * @param tx the FAILED transaction whose operations have drained
     * @throws IllegalStateException if tx is committed
     * @throws IOException if undo/logging fails; CCM retains FAILED and all protection
     */
    void rollback(TxId tx) throws IOException;

    /**
     * Completes startup recovery before any transaction is admitted. Returns recorded ID and
     * logical timestamp high-water marks so CCM can safely reseed both generators.
     *
     * @return recovered ID/timestamp high-water marks, never null
     * @throws IOException if recovery fails; admission must remain closed
     */
    RecoveryInfo recover() throws IOException;
}
