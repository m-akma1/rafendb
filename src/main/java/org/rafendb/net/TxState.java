package org.rafendb.net;

import java.net.ProtocolException;

/** Transaction state of a session as carried by {@link MessageType#READY}; it drives the prompt. */
public enum TxState {
    /** No transaction open; statements auto-commit. */
    IDLE('I'),
    /** Inside BEGIN ... COMMIT. */
    ACTIVE('T'),
    /** The open transaction failed or was aborted and waits for ABORT or COMMIT. */
    FAILED('F');

    private final char code;

    TxState(char code) {
        this.code = code;
    }

    /**
     * Returns the one-character payload of a {@link MessageType#READY} message.
     *
     * @return the code
     */
    public String code() {
        return String.valueOf(code);
    }

    /**
     * Parses the payload of a {@link MessageType#READY} message.
     *
     * @param code the payload
     * @return the state
     * @throws ProtocolException if the payload is no state code
     */
    public static TxState fromCode(String code) throws ProtocolException {
        for (TxState state : values()) {
            if (state.code().equals(code)) {
                return state;
            }
        }
        throw new ProtocolException("unknown transaction state: " + code);
    }
}
