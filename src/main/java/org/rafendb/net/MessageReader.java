package org.rafendb.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;

/**
 * Reads messages written by {@link MessageWriter}.
 *
 * <p>The payload is read in steps of at most {@link #STEP} bytes, so memory grows only with bytes
 * that actually arrive: a corrupt or hostile length ends in {@link EOFException}, not in a huge
 * allocation up front.
 *
 * <p>Not thread-safe: one thread reads a connection.
 */
public final class MessageReader {
    private static final int STEP = 64 * 1024;

    private final DataInputStream in;

    /**
     * Creates a reader.
     *
     * @param in the stream to read from, usually a socket's
     */
    public MessageReader(InputStream in) {
        this.in = new DataInputStream(new BufferedInputStream(in));
    }

    /**
     * Reads the next message.
     *
     * @return the message, or {@code null} if the stream ended cleanly before a new message
     * @throws ProtocolException if the type byte is unknown or the length is negative
     * @throws EOFException if the stream ends inside a message
     * @throws IOException if reading fails
     */
    public Message read() throws IOException {
        int code = in.read();
        if (code < 0) {
            return null;
        }
        MessageType type = MessageType.fromCode(code);
        int length = in.readInt();
        if (length < 0) {
            throw new ProtocolException("negative payload length: " + length);
        }
        return new Message(type, readPayload(length));
    }

    private String readPayload(int length) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream(Math.min(length, STEP));
        byte[] step = new byte[Math.min(length, STEP)];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(step, 0, Math.min(remaining, step.length));
            if (n < 0) {
                throw new EOFException("stream ended inside a message");
            }
            payload.write(step, 0, n);
            remaining -= n;
        }
        return payload.toString(StandardCharsets.UTF_8);
    }
}
