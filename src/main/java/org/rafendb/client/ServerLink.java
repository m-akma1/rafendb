package org.rafendb.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import org.rafendb.net.Message;
import org.rafendb.net.MessageReader;
import org.rafendb.net.MessageType;
import org.rafendb.net.MessageWriter;

/**
 * One connection to the server. A background thread reads every message, so the end of the
 * connection is noticed even while the user sits at the prompt: an unsolicited {@code CLOSING} or
 * a lost connection is then reported to the listener given to {@link #open}. During {@link
 * #exchange} the reply, including such an end, goes to the caller instead.
 */
final class ServerLink implements AutoCloseable {
    /** Marks the end of the stream in the reply list; compared by identity. */
    static final Message LOST = new Message(MessageType.CLOSING, "");

    private static final int CONNECT_TIMEOUT_MILLIS = 5000;

    final String host;
    final int port;
    final String session;
    final String version;
    final String protocol;

    private final Socket socket;
    private final MessageReader reader;
    private final MessageWriter writer;
    private final Consumer<Message> onIdleEnd;
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private boolean awaiting;
    private boolean ended;

    private ServerLink(
            String host,
            int port,
            Socket socket,
            Map<String, String> hello,
            MessageReader reader,
            Consumer<Message> onIdleEnd)
            throws IOException {
        this.host = host;
        this.port = port;
        this.socket = socket;
        this.reader = reader;
        this.writer = new MessageWriter(socket.getOutputStream());
        this.onIdleEnd = onIdleEnd;
        this.session = hello.getOrDefault("session", "?");
        this.version = hello.getOrDefault("version", "?");
        this.protocol = hello.getOrDefault("protocol", "?");
    }

    /**
     * Connects and reads the server's {@code HELLO}.
     *
     * @param host the server host
     * @param port the server port
     * @param onIdleEnd told about a {@code CLOSING} or {@link #LOST} that arrives while no request
     *     is waiting for a reply; runs on the reader thread
     * @return the open link
     * @throws IOException if the server cannot be reached or does not greet
     */
    static ServerLink open(String host, int port, Consumer<Message> onIdleEnd) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            MessageReader reader = new MessageReader(socket.getInputStream());
            Message hello = reader.read();
            if (hello == null || hello.type() != MessageType.HELLO) {
                throw new IOException("the server did not greet");
            }
            ServerLink link = new ServerLink(host, port, socket, fields(hello), reader, onIdleEnd);
            Thread.ofVirtual().name("rafendb-client-reader").start(link::readLoop);
            return link;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /**
     * Sends a request and collects its reply: every message up to {@code READY}, or up to the end
     * of the connection ({@code CLOSING} or {@link #LOST}), which is then the last element.
     *
     * @param request the request
     * @param untilEnd also wait past {@code READY} for the end of the connection, as after {@code
     *     SHUTDOWN}
     * @return the reply, never empty
     */
    List<Message> exchange(Message request, boolean untilEnd) {
        synchronized (lock) {
            if (ended) {
                return List.of(LOST);
            }
            awaiting = true;
        }
        List<Message> reply = new ArrayList<>();
        try {
            writer.write(request);
            while (true) {
                Message message = inbox.take();
                reply.add(message);
                if (isEnd(message) || (message.type() == MessageType.READY && !untilEnd)) {
                    break;
                }
            }
        } catch (IOException e) {
            reply.add(LOST);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reply.add(LOST);
        } finally {
            synchronized (lock) {
                awaiting = false;
            }
        }
        return reply;
    }

    /**
     * Asks the server to cancel the statement that is waiting for its reply. Safe to call from
     * another thread.
     *
     * @return whether a cancel was sent; false when no statement is waiting
     */
    boolean cancel() {
        synchronized (lock) {
            if (!awaiting || ended) {
                return false;
            }
        }
        try {
            writer.write(Message.of(MessageType.CANCEL));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns whether the connection has ended.
     *
     * @return true after {@code CLOSING} or a lost connection
     */
    boolean isEnded() {
        synchronized (lock) {
            return ended;
        }
    }

    /** Says {@code BYE} if still connected, then closes the socket. Idempotent. */
    @Override
    public void close() {
        boolean polite;
        synchronized (lock) {
            polite = !ended;
            ended = true;
        }
        if (polite) {
            try {
                writer.write(Message.of(MessageType.BYE));
            } catch (IOException _) {
            }
        }
        try {
            socket.close();
        } catch (IOException _) {
        }
    }

    static boolean isEnd(Message message) {
        return message == LOST || message.type() == MessageType.CLOSING;
    }

    private void readLoop() {
        try {
            for (Message m = reader.read(); m != null; m = reader.read()) {
                deliver(m);
            }
        } catch (IOException e) {
            // Treated as a lost connection below.
        }
        deliver(LOST);
    }

    private void deliver(Message message) {
        boolean tellListener;
        synchronized (lock) {
            if (isEnd(message)) {
                if (ended && !awaiting) {
                    return; // already over: a second end, or closed by us
                }
                ended = true;
            }
            tellListener = isEnd(message) && !awaiting;
            if (!tellListener) {
                inbox.add(message);
            }
        }
        if (tellListener) {
            onIdleEnd.accept(message);
        }
    }

    private static Map<String, String> fields(Message hello) {
        Map<String, String> fields = new HashMap<>();
        for (String line : hello.payload().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return fields;
    }
}
