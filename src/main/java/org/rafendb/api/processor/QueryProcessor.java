package org.rafendb.api.processor;

import org.rafendb.common.QueryResult;

public interface QueryProcessor {

    /**
     * Start a new session with a client
     * @return sessionId
     */
    int openSession();

    /**
     * Execute a raw query from client with identified sessionId
     * @param sql the raw sql string
     * @param sessionId client's sessionId
     * @return {@link QueryResult}
     */
    QueryResult execute(String sql, int sessionId);

    /**
     * Close a session with the specified sessionId
     * @param sessionId
     */
    void closeSession(int sessionId);
}
