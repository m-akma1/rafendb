package org.rafendb.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageReaderWriterTest {

    @Test
    void replySequenceRoundTripsAndEndsCleanly() throws IOException {
        List<Message> sent =
                List.of(
                        new Message(MessageType.HELLO, "session=4\nversion=dev"),
                        new Message(MessageType.RESULT, "| nama |\n| Café ☕ |"),
                        new Message(MessageType.NOTICE, "heads up"),
                        new Message(MessageType.ERROR, "bad statement"),
                        Message.ready(TxState.FAILED),
                        Message.of(MessageType.BYE));

        MessageReader reader = new MessageReader(new ByteArrayInputStream(bytes(sent)));

        assertEquals(sent, readAll(reader));
        assertEquals(TxState.FAILED, TxState.fromCode(sent.get(4).payload()));
    }

    @Test
    void largeResultIsChunkedAndReassembles() throws IOException {
        // An emoji is a surrogate pair; place one across the first chunk boundary.
        String text =
                "x".repeat(MessageWriter.CHUNK_CHARS - 1)
                        + "😀"
                        + "y".repeat(MessageWriter.CHUNK_CHARS * 2);

        List<Message> received =
                readAll(
                        new MessageReader(
                                new ByteArrayInputStream(
                                        bytes(List.of(new Message(MessageType.RESULT, text))))));

        assertTrue(received.size() > 1);
        StringBuilder joined = new StringBuilder();
        for (Message message : received) {
            assertEquals(MessageType.RESULT, message.type());
            joined.append(message.payload());
        }
        assertEquals(text, joined.toString());
    }

    @Test
    void malformedInputIsRejected() {
        // Declares ~2 GiB but only 2 bytes follow: must end in EOF, not allocate the whole length.
        assertThrows(EOFException.class, () -> read('R', 0x7F, 0xFF, 0xFF, 0xFF, 'a', 'b'));
        assertThrows(ProtocolException.class, () -> read('?', 0, 0, 0, 0));
        assertThrows(ProtocolException.class, () -> read('R', 0xFF, 0xFF, 0xFF, 0xFF));
    }

    private static byte[] bytes(List<Message> messages) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MessageWriter(out).write(messages.toArray(Message[]::new));
        return out.toByteArray();
    }

    private static List<Message> readAll(MessageReader reader) throws IOException {
        List<Message> messages = new ArrayList<>();
        for (Message m = reader.read(); m != null; m = reader.read()) {
            messages.add(m);
        }
        assertNull(reader.read());
        return messages;
    }

    private static Message read(int... raw) throws IOException {
        byte[] b = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            b[i] = (byte) raw[i];
        }
        return new MessageReader(new ByteArrayInputStream(b)).read();
    }
}
