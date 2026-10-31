package org.rafendb.net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class Protocol {
    static final int MAX_FRAME = 256;

    public static void writeFrame(DataOutputStream out, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);

        out.writeInt(b.length);
        out.write(b);
        out.flush();
    }

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
