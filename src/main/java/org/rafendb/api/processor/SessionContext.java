package org.rafendb.api.processor;

public class SessionContext {
    private final int sessionId;
    private int currentTxId;
    private volatile boolean valid;

    public SessionContext(int sessionId, int currentTxId, boolean valid) {
        this.sessionId = sessionId;
        this.currentTxId = -1;
        this.valid = true;
    }

    public void invalidate() {
        this.valid = false;
    }

    public int getSessionId() {
        return this.sessionId;
    }

    public int getCurrentTxId() {
        return this.currentTxId;
    }

    public boolean isValid() {
        return this.valid;
    }

    public synchronized void setCurrentTxId(int txId) {
        this.currentTxId = txId;
    }
}
