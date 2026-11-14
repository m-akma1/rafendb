package org.rafendb.processor.txn;

import java.util.Objects;
import java.util.function.Supplier;
import org.rafendb.common.error.ConcurrencyInternalException;

/**
 * Auto-commit: runs one statement as its own transaction when the session has none.
 *
 * <p>Whether a transaction is "explicit" is decided from {@code SessionContext.currentTxId} at the
 * start of each statement, so a script such as {@code BEGIN; a; b; COMMIT;} is never committed in
 * the middle, while {@code a; b;} without BEGIN commits after each statement.
 *
 * <p>Wrap anything that touches data (SELECT, INSERT, UPDATE, DELETE), not BEGIN/COMMIT/ABORT.
 */
public final class AutoCommit {
    private AutoCommit() {}

    /**
     * @param coordinator the session's coordinator
     * @param statement the work; for DML, it executes the translated command
     * @return whatever {@code statement} returns
     */
    public static <T> T run(TransactionCoordinator coordinator, Supplier<T> statement)
            throws ConcurrencyInternalException {
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(statement, "statement");

        boolean implicit = coordinator.getExecutionContext().getSContext().getCurrentTxId() == null;
        if (implicit) {
            coordinator.beginTx();
        }

        T result;
        try {
            result = statement.get();
        } catch (RuntimeException | Error failure) {
            if (implicit) {
                abortQuietly(coordinator, failure);
            }
            // Explicit transaction: stays open unless CCM aborted it (DML already cleared it then).
            throw failure;
        }

        if (implicit) {
            coordinator.commitTx();
        }
        return result;
    }

    private static void abortQuietly(TransactionCoordinator coordinator, Throwable primary) {
        try {
            coordinator.abortTx();
        } catch (ConcurrencyInternalException | RuntimeException e) {
            primary.addSuppressed(e);
        }
    }
}
