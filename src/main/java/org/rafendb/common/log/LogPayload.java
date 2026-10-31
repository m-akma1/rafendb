package org.rafendb.common.log;

import java.util.Objects;
import java.util.Optional;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.TxId;

/**
 * Content of one write-ahead log record, passed to {@link
 * org.rafendb.api.recovery.RecoveryManager#writeLog}. The LSN is assigned by FRM, not stored here.
 *
 * <p>Data changes ({@link LogType#isDataChange()}) carry the {@link ChangeRecord} as given by CCM;
 * every other kind carries none. The change itself is not validated here; see {@link ChangeRecord}.
 *
 * <p>Immutable and thread-safe, as long as the image arrays inside {@code change} are not modified.
 *
 * @param tx the transaction that wrote the record, not null
 * @param type the record kind, not null
 * @param timestamp wall-clock time of the record in epoch milliseconds, used for point-in-time
 *     recovery; not {@link org.rafendb.common.txn.RecoveryInfo#maxTimestamp}, which is logical
 * @param change the row change for data changes, empty otherwise; not null
 */
public record LogPayload(TxId tx, LogType type, long timestamp, Optional<ChangeRecord> change) {

    /**
     * Checks the arguments.
     *
     * @throws NullPointerException if {@code tx}, {@code type} or {@code change} is null
     * @throws IllegalArgumentException if {@code change} is present for a kind that is not a data
     *     change, or empty for one that is
     */
    public LogPayload {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(change, "change");
        if (change.isPresent() != type.isDataChange()) {
            throw new IllegalArgumentException(
                    type + (type.isDataChange() ? " needs a change" : " takes no change"));
        }
    }

    /**
     * Returns a data change record stamped with the current time.
     *
     * @param tx the transaction, not null
     * @param type {@link LogType#INSERT}, {@link LogType#UPDATE} or {@link LogType#DELETE}
     * @param change the row change, not null
     * @return the payload
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code type} is not a data change
     */
    public static LogPayload ofChange(TxId tx, LogType type, ChangeRecord change) {
        return new LogPayload(
                tx,
                type,
                System.currentTimeMillis(),
                Optional.of(Objects.requireNonNull(change, "change")));
    }

    /**
     * Returns a transaction or checkpoint record stamped with the current time.
     *
     * @param tx the transaction, not null
     * @param type a kind that is not a data change
     * @return the payload
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if {@code type} is a data change
     */
    public static LogPayload ofEvent(TxId tx, LogType type) {
        return new LogPayload(tx, type, System.currentTimeMillis(), Optional.empty());
    }
}
