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
     * Closes the session and aborts its open transaction, if any, without disturbing other
     * sessions (QP-02). Idempotent.
     */
    @Override
    void close();
}
