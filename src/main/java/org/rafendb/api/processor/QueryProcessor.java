package org.rafendb.api.processor;

/**
 * Entry point of the Query Processor (QP). Hands out one {@link Session} per connected client
 * (QP-02); all SQL runs through a session.
 */
public interface QueryProcessor {

    /**
     * Opens a new session for one client. Thread-safe: connection threads may call it
     * concurrently.
     *
     * @return a new open session, never {@code null}
     */
    Session openSession();
}
