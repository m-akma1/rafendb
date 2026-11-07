package org.rafendb.api.processor;

/**
 * Stores the state and transaction information associated with a client session.
 *
 * <p>Each client session maintains its own session ID, current transaction ID,
 * and validity status. This context allows query operators and other components
 * to access and manage session-related information during query execution.</p>
 *
 * <p>The validity flag is declared as {@code volatile} to ensure that changes
 * made by one thread are visible to other threads.</p>
 */
public class SessionContext {
    private final int sessionId;
    private int currentTxId;
    private volatile boolean valid;

    /**
     * Constructs a SessionContext with the specified session information.
     *
     * @param sessionId the unique identifier of the client session
     */
    public SessionContext(int sessionId) {
        this.sessionId = sessionId;
        this.currentTxId = -1;
        this.valid = true;
    }

    /**
     * Marks this session as invalid.
     *
     * <p>This method can be called when a client disconnects or when the
     * session is no longer allowed to process queries.</p>
     */
    public void invalidate() {
        this.valid = false;
    }

    /**
     * Returns the unique identifier of this session.
     *
     * @return the session ID
     */
    public int getSessionId() {
        return this.sessionId;
    }

    /**
     * Returns the ID of the transaction currently associated with this session.
     *
     * @return the current transaction ID, or {@code -1} if no transaction is active
     */
    public int getCurrentTxId() {
        return this.currentTxId;
    }

    /**
     * Checks whether this session is still valid.
     *
     * @return {@code true} if the session is valid; {@code false} otherwise
     */
    public boolean isValid() {
        return this.valid;
    }

    /**
     * Updates the transaction ID associated with this session.
     *
     * <p>This method is synchronized to prevent concurrent threads from
     * modifying the transaction ID simultaneously.</p>
     *
     * @param txId the transaction ID to associate with this session
     */
    public synchronized void setCurrentTxId(int txId) {
        this.currentTxId = txId;
    }
}
