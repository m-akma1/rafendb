package org.rafendb.api.processor;

import org.rafendb.common.query.QueryResult;

/**
 * One client's session (QP-02): its own transaction state and auto-commit behaviour. Obtained
 * from {@link QueryProcessor#openSession()} and closed when the client disconnects.
 *
 * <p>Not thread-safe: a session is used by one connection thread at a time. Different sessions
 * may run concurrently.
 */
public interface Session extends AutoCloseable {

    /**
     * Transaction state of a session, reported after each statement (QP-01, QP-02). The server
     * sends it to the client, which shows it in the prompt.
     */
    enum TxState {
        /** No transaction is open; each statement runs as its own transaction (auto-commit). */
        IDLE,
        /** An explicit transaction opened by BEGIN is running. */
        ACTIVE,
        /**
         * The explicit transaction failed or was aborted by the system and has been rolled back;
         * the session rejects every statement until the client ends it with ABORT.
         */
        FAILED
    }

    /**
     * Runs one SQL statement. A trailing {@code ;} is optional. Outside BEGIN ... COMMIT the
     * statement runs as its own transaction (QP-12).
     *
     * @param sql the statement text, not null
     * @return the result, never {@code null}
     * @throws org.rafendb.common.error.RafenDBRuntimeException if the statement fails in a way
     *     the user should see (syntax, validation, transaction rejected); the message is shown to
     *     the user and the session stays usable
     * @throws IllegalStateException if the session is closed
     * @throws NullPointerException if {@code sql} is null
     */
    QueryResult execute(String sql);

    /**
     * Returns the session's transaction state after the last statement. May be called by another
     * thread than the one running {@link #execute}, for example while the server shuts down.
     * Still answers after {@link #close()}, then {@link TxState#IDLE}.
     *
     * @return the state, never {@code null}
     */
    TxState transactionState();

    /**
     * Closes the session and aborts its open transaction, if any, without disturbing other
     * sessions (QP-02). Idempotent.
     */
    @Override
    void close();
}
