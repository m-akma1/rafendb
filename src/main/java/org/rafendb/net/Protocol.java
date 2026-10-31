package org.rafendb.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Length-prefixed text frames between client and server: a 4-byte length, then that many UTF-8
 * bytes. The client sends one frame per statement and the server answers each with exactly one
 * frame.
 */
public final class Protocol {
    /** Largest frame payload in bytes, checked on both write and read. */
    static final int MAX_FRAME = 16 * 1024 * 1024;

    private Protocol() {}

    /**
     * Writes one frame and flushes.
     *
     * @param out the stream to write to
     * @param msg the text to send
     * @throws IOException if writing fails or the encoded text is larger than {@link #MAX_FRAME}
     */
    public static void writeFrame(DataOutputStream out, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_FRAME) throw new IOException("Frame too large: " + b.length + " bytes");

        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

    /**
     * Reads one frame.
     *
     * @param in the stream to read from
     * @return the text, or {@code null} if the stream ended cleanly before a new frame
     * @throws IOException if reading fails or the length is negative or larger than {@link
     *     #MAX_FRAME}
     */
    public static String readFrame(DataInputStream in) throws IOException {
        int len;
        try {
            len = in.readInt();
        } catch (EOFException e) {
            return null;
        }

        if (len < 0 || len > MAX_FRAME) throw new IOException("Bad frame length: " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
