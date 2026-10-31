package org.rafendb.common.log;

/**
 * Kind of a write-ahead log (WAL) record, following the FRM class diagram.
 *
 * <p>Immutable and thread-safe.
 */
public enum LogType {
    /** A transaction started. */
    BEGIN,
    /** A transaction committed; written and forced before the commit is reported as successful. */
    COMMIT,
    /** A transaction's rollback finished; written after all its changes are undone. */
    ABORT,
    /** A record was inserted; it has no before-image. */
    INSERT,
    /** A record was updated; it has both a before-image and an after-image. */
    UPDATE,
    /** A record was deleted; it has no after-image. */
    DELETE,
    /** A checkpoint started. */
    CHECKPOINT_BEGIN,
    /** A checkpoint finished. */
    CHECKPOINT_END;

    /**
     * Returns whether a record of this kind changes table data and can therefore be undone or redone.
     *
     * @return {@code true} for {@link #INSERT}, {@link #UPDATE} and {@link #DELETE}
     */
    public boolean isDataChange() {
        return this == INSERT || this == UPDATE || this == DELETE;
    }

    /**
     * Returns whether a record of this kind marks a transaction lifecycle event.
     *
     * @return {@code true} for {@link #BEGIN}, {@link #COMMIT} and {@link #ABORT}
     */
    public boolean isTransactionEvent() {
        return this == BEGIN || this == COMMIT || this == ABORT;
    }

    /**
     * Returns whether a record of this kind marks a checkpoint.
     *
     * @return {@code true} for {@link #CHECKPOINT_BEGIN} and {@link #CHECKPOINT_END}
     */
    public boolean isCheckpoint() {
        return this == CHECKPOINT_BEGIN || this == CHECKPOINT_END;
    }

    /**
     * Returns the kind of change that reverses a change of this kind: INSERT and DELETE swap, UPDATE
     * stays UPDATE.
     *
     * @return the inverse change kind
     * @throws IllegalStateException if this kind is not a data change
     */
    public LogType inverse() {
        return switch (this) {
            case INSERT -> DELETE;
            case DELETE -> INSERT;
            case UPDATE -> UPDATE;
            default -> throw new IllegalStateException(this + " is not a data change");
        };
    }
}
