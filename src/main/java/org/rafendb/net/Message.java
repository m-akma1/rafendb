package org.rafendb.net;

import java.util.Objects;

/**
 * One message on the wire.
 *
 * @param type the kind of message, not null
 * @param payload the UTF-8 text it carries, empty when the type has none, not null
 */
public record Message(MessageType type, String payload) {

    /** Rejects {@code null} components. */
    public Message {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
    }

    /**
     * Creates a message with an empty payload, such as {@link MessageType#BYE}.
     *
     * @param type the kind of message
     * @return the message
     */
    public static Message of(MessageType type) {
        return new Message(type, "");
    }

    /**
     * Creates the {@link MessageType#READY} message that ends a reply.
     *
     * @param state the session's transaction state after the statement
     * @return the message
     */
    public static Message ready(TxState state) {
        return new Message(MessageType.READY, state.code());
    }
}
