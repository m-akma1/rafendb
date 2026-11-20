package org.rafendb.net;

import java.net.ProtocolException;

/**
 * The kinds of message on the wire, each sent as one ASCII type byte. Every {@link #QUERY} is
 * answered by {@code (RESULT | NOTICE)* ERROR? READY}; {@link #HELLO} opens a connection and
 * {@link #CLOSING} may arrive at any time.
 */
public enum MessageType {
    /** Server to client, once after accept: {@code key=value} lines (session, version, protocol). */
    HELLO('H'),
    /** Client to server: one SQL statement, already split by the client. */
    QUERY('Q'),
    /** Client to server: cancel the running statement. Empty payload. */
    CANCEL('C'),
    /** Client to server: shut the server down. Empty payload. */
    SHUTDOWN('D'),
    /** Client to server: the client is leaving. Empty payload; a plain EOF means the same. */
    BYE('X'),
    /** Server to client: a chunk of rendered result text; consecutive chunks are concatenated. */
    RESULT('R'),
    /** Server to client: one informational line, inside a reply or unsolicited. */
    NOTICE('N'),
    /** Server to client: the error line of a failed statement, shown as is. */
    ERROR('E'),
    /** Server to client: ends every reply; the payload is a {@link TxState} code. */
    READY('Z'),
    /** Server to client: the server is closing this connection; the payload is the reason. */
    CLOSING('B');

    private static final MessageType[] BY_CODE = new MessageType[128];

    static {
        for (MessageType type : values()) {
            BY_CODE[type.code] = type;
        }
    }

    private final char code;

    MessageType(char code) {
        this.code = code;
    }

    /**
     * Returns the type byte written on the wire.
     *
     * @return the ASCII code
     */
    public char code() {
        return code;
    }

    /**
     * Finds the type of a type byte.
     *
     * @param code the byte read from the wire, 0 to 255
     * @return the type
     * @throws ProtocolException if no type has that code
     */
    public static MessageType fromCode(int code) throws ProtocolException {
        MessageType type = code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
        if (type == null) {
            throw new ProtocolException("unknown message type: " + code);
        }
        return type;
    }
}
