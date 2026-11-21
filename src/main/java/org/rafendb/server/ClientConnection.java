package org.rafendb.server;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Supplier;
import org.rafendb.api.processor.Session;
import org.rafendb.common.Component;
import org.rafendb.common.error.RafenDBInternalException;
import org.rafendb.common.error.RafenDBRuntimeException;
import org.rafendb.engine.MetaCommands;
import org.rafendb.engine.Version;
import org.rafendb.net.Message;
import org.rafendb.net.MessageReader;
import org.rafendb.net.MessageType;
import org.rafendb.net.MessageWriter;
import org.rafendb.net.TxState;

/**
 * One client connection with its own {@link Session} (QP-02), served on its own virtual thread.
 * Sends {@code HELLO}, then answers every {@code QUERY} and {@code META} with {@code (RESULT | NOTICE)* ERROR?
 * READY}. When it ends, for any reason, its session is closed, which aborts an open transaction.
 *
 * <p>Errors follow {@code common.error}: a {@link RafenDBRuntimeException} message is sent as is;
 * anything else is a bug that escaped a component, so the client only learns which component is
 * to blame and the details go to the server log.
 */
final class ClientConnection implements Runnable {
    /** The reason sent with {@code CLOSING} when the server shuts down. */
    static final String SHUTDOWN_REASON = "Server is shutting down.";

    private final int id;
    private final Socket socket;
    private final ServerMain server;
    private final MessageReader reader;
    private final MessageWriter writer;
    private final Object lock = new Object();
    private boolean executing;
    private boolean closing;
    private Thread thread;

    ClientConnection(int id, Socket socket, ServerMain server) throws IOException {
        this.id = id;
        this.socket = socket;
        this.server = server;
        this.reader = new MessageReader(socket.getInputStream());
        this.writer = new MessageWriter(socket.getOutputStream());
    }

    int id() {
        return id;
    }

    String tag() {
        return "[Session #" + id + "]";
    }

    void start() {
        thread = Thread.ofVirtual().name("rafendb-session-" + id).start(this);
    }

    @Override
    public void run() {
        server.log(tag() + " connected from " + socket.getRemoteSocketAddress());
        Session session = null;
        try {
            session = server.processor().openSession();
            writer.write(new Message(MessageType.HELLO, hello()));
            serve(session);
        } catch (IOException e) {
            if (!isClosing()) {
                server.log(tag() + " connection error: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            System.err.println(tag() + " failed unexpectedly:");
            e.printStackTrace();
        } finally {
            boolean openTransaction = false;
            if (session != null) {
                try {
                    openTransaction = session.transactionState() != Session.TxState.IDLE;
                    session.close();
                } catch (RuntimeException e) {
                    System.err.println(tag() + " failed to close its session:");
                    e.printStackTrace();
                }
            }
            ServerMain.closeQuietly(socket);
            server.remove(this);
            server.log(
                    tag()
                            + " disconnected"
                            + (openTransaction ? " (open transaction aborted)" : ""));
        }
    }

    /** Reads requests until the client leaves or the server closes this connection. */
    private void serve(Session session) throws IOException {
        for (Message request = reader.read(); request != null; request = reader.read()) {
            switch (request.type()) {
                case QUERY -> {
                    String sql = request.payload();
                    if (!beginReply()
                            || !finishReply(
                                    execute(
                                            session,
                                            sql,
                                            () -> ResultRenderer.render(session.execute(sql))))) {
                        return;
                    }
                }
                case META -> {
                    String line = request.payload();
                    if (!beginReply()
                            || !finishReply(
                                    execute(
                                            session,
                                            line,
                                            () ->
                                                    renderSections(
                                                            server.metaCommands().run(line))))) {
                        return;
                    }
                }
                case SHUTDOWN -> {
                    if (!beginReply()) {
                        return;
                    }
                    // The CLOSING that the shutdown sends next carries the message.
                    boolean keepServing = finishReply(ready(session));
                    server.shutdownAsync("requested by session #" + id);
                    if (!keepServing) {
                        return;
                    }
                }
                case CANCEL -> server.log(tag() + " asked to cancel. Not supported yet, ignored.");
                case BYE -> {
                    return;
                }
                default -> {
                    server.log(tag() + " protocol error: unexpected " + request.type() + ".");
                    return;
                }
            }
        }
    }

    /**
     * Runs one statement or meta-command and builds its reply; never throws for a failed one.
     *
     * @param request what the client sent, for the server log
     * @param run runs it and returns the rendered result
     */
    private Message[] execute(Session session, String request, Supplier<String> run) {
        Message outcome;
        try {
            outcome = new Message(MessageType.RESULT, run.get());
        } catch (RafenDBRuntimeException e) {
            outcome = new Message(MessageType.ERROR, e.getMessage());
        } catch (RuntimeException e) {
            Component blamed = blame(e);
            System.err.println(
                    tag() + " internal error at " + blamed + " while running: " + request);
            e.printStackTrace();
            outcome =
                    new Message(
                            MessageType.ERROR,
                            "Internal error at "
                                    + blamed.displayName()
                                    + ". See the details in server log session #"
                                    + id);
        }
        return new Message[] {outcome, ready(session)};
    }

    /**
     * Marks a reply as started, so a shutdown lets it finish instead of cutting it.
     *
     * @return false if the server is already closing this connection
     */
    private boolean beginReply() {
        synchronized (lock) {
            if (closing) {
                return false;
            }
            executing = true;
            return true;
        }
    }

    /**
     * Sends a reply, followed by {@code CLOSING} if a shutdown came in meanwhile.
     *
     * @return false if this connection must end now
     */
    private boolean finishReply(Message... reply) throws IOException {
        writer.write(reply);
        synchronized (lock) {
            executing = false;
            if (closing) {
                sendClosing();
                return false;
            }
            return true;
        }
    }

    /**
     * Asks this connection to end because the server shuts down. An idle connection gets {@code
     * CLOSING} and is closed at once; a running statement first finishes and sends its reply.
     */
    void requestClose() {
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            if (!executing) {
                sendClosing();
                ServerMain.closeQuietly(socket);
            }
        }
    }

    /**
     * Waits for this connection's thread to end.
     *
     * @param timeout the longest time to wait
     * @return whether it ended
     */
    boolean awaitEnd(Duration timeout) {
        try {
            return thread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Cuts the connection of a statement that did not finish in time. */
    void forceClose() {
        thread.interrupt();
        ServerMain.closeQuietly(socket);
    }

    private boolean isClosing() {
        synchronized (lock) {
            return closing;
        }
    }

    private void sendClosing() {
        try {
            writer.write(new Message(MessageType.CLOSING, SHUTDOWN_REASON));
        } catch (IOException ignored) {
            // The client is already gone; closing the socket follows anyway.
        }
    }

    /** Renders each section, its title above it, separated by a blank line. */
    private static String renderSections(List<MetaCommands.Section> sections) {
        StringJoiner text = new StringJoiner("\n\n");
        for (MetaCommands.Section section : sections) {
            String rows = ResultRenderer.render(section.rows());
            text.add(section.title() == null ? rows : section.title() + "\n" + rows);
        }
        return text.toString();
    }

    private String hello() {
        return "session="
                + id
                + "\nversion="
                + Version.current()
                + "\nprotocol="
                + server.protocol();
    }

    /**
     * Decides which component to blame for a failure that escaped the session, so the client
     * learns where to look without seeing the internal message:
     *
     * <ol>
     *   <li>the first RafenDB exception in the cause chain names its own component;
     *   <li>otherwise the topmost stack frame of a component package ({@code org.rafendb.<x>},
     *       {@code api.<x>} or {@code mock.<x>}) owns it; {@code common} is skipped, since its
     *       shared types only check what their caller passed;
     *   <li>otherwise {@link Component#GENERAL}.
     * </ol>
     */
    private static Component blame(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof RafenDBRuntimeException e) return e.component();
            if (t instanceof RafenDBInternalException e) return e.component();
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            String[] parts = frame.getClassName().split("\\.");
            if (parts.length < 3 || !parts[0].equals("org") || !parts[1].equals("rafendb")) {
                continue;
            }
            String area = parts[2];
            if ((area.equals("api") || area.equals("mock")) && parts.length > 3) {
                area = parts[3];
            }
            if (area.equals("common")) {
                continue;
            }
            return switch (area) {
                case "processor" -> Component.QP;
                case "optimizer" -> Component.QO;
                case "storage" -> Component.SM;
                case "concurrency" -> Component.CCM;
                case "recovery" -> Component.FRM;
                default -> Component.GENERAL;
            };
        }
        return Component.GENERAL;
    }

    private static Message ready(Session session) {
        TxState state =
                switch (session.transactionState()) {
                    case IDLE -> TxState.IDLE;
                    case ACTIVE -> TxState.ACTIVE;
                    case FAILED -> TxState.FAILED;
                };
        return Message.ready(state);
    }
}
