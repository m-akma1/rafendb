package org.rafendb.common.txn;

import java.util.Objects;

/**
 * Answer of the CCM to an access request: allowed, wait, or aborted.
 *
 * @param status the decision
 * @param message text for the user; empty unless aborted
 */
public record AccessResult(Status status, String message) {
    /** The three possible decisions. */
    public enum Status {
        /** Access granted. */
        ALLOWED,
        /** Not granted yet; the caller asks again later. */
        WAITING,
        /** The CCM aborted the transaction; it can no longer be used. */
        ABORTED
    }

    /** @throws NullPointerException if an argument is null */
    public AccessResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(message, "message");
    }

    /** Returns an {@link Status#ALLOWED} result. */
    public static AccessResult allowed() {
        return new AccessResult(Status.ALLOWED, "");
    }

    /** Returns a {@link Status#WAITING} result. */
    public static AccessResult waiting() {
        return new AccessResult(Status.WAITING, "");
    }

    /** Returns an {@link Status#ABORTED} result carrying the reason shown to the user. */
    public static AccessResult aborted(String message) {
        return new AccessResult(Status.ABORTED, message);
    }
}
