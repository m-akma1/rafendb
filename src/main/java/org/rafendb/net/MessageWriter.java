package org.rafendb.net;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes messages: a type byte, a 4-byte big-endian payload length, then the UTF-8 payload.
 *
 * <p>A long {@link MessageType#RESULT} is split into several RESULT messages of at most {@link
 * #CHUNK_CHARS} characters, which the reader side concatenates. The chunk size is this writer's
 * choice, not a protocol limit.
 *
 * <p>Thread-safe: one {@link #write} call is sent as a whole, never interleaved with another, so a
 * reply and an unsolicited {@link MessageType#CLOSING} from another thread cannot mix.
 */
public final class MessageWriter {
    /** Largest RESULT chunk in characters; at most 4 UTF-8 bytes each, so at most 64 KiB. */
    static final int CHUNK_CHARS = 16 * 1024;

    private final DataOutputStream out;

    /**
     * Creates a writer.
     *
     * @param out the stream to write to, usually a socket's
     */
    public MessageWriter(OutputStream out) {
        this.out = new DataOutputStream(new BufferedOutputStream(out));
    }

    /**
     * Writes the messages in order, then flushes.
     *
     * @param messages the messages, none null
     * @throws IOException if writing fails
     */
    public synchronized void write(Message... messages) throws IOException {
        for (Message message : messages) {
            if (message.type() == MessageType.RESULT) {
                writeChunked(message.payload());
            } else {
                writeOne(message.type(), message.payload());
            }
        }
        out.flush();
    }

    private void writeChunked(String text) throws IOException {
        int start = 0;
        do {
            int end = Math.min(text.length(), start + CHUNK_CHARS);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--; // never split a surrogate pair across chunks
            }
            writeOne(MessageType.RESULT, text.substring(start, end));
            start = end;
        } while (start < text.length());
    }

    private void writeOne(MessageType type, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        out.writeByte(type.code());
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
