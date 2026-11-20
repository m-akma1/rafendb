package org.rafendb.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.api.processor.Session;
import org.rafendb.common.Component;
import org.rafendb.common.error.ConcurrencyInternalException;
import org.rafendb.common.error.ProcessorRuntimeException;
import org.rafendb.common.query.QueryResult;
import org.rafendb.net.Message;
import org.rafendb.net.MessageReader;
import org.rafendb.net.MessageType;
import org.rafendb.net.MessageWriter;
import org.rafendb.net.TxState;

class ServerTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final RecordingProcessor processor = new RecordingProcessor();
    private ServerMain server;

    @BeforeEach
    void start() throws IOException {
        server = new ServerMain(new ServerConfig(0, WAIT), processor, ProtocolType.LOCK_BASED);
        server.start();
    }

    @AfterEach
    void stop() {
        server.shutdown("test finished");
    }

    @Test
    void eachClientHasItsOwnSession() throws IOException {
        try (TestClient a = connect();
                TestClient b = connect()) {
            assertNotEquals(a.sessionId, b.sessionId);

            assertEquals(TxState.ACTIVE, a.query("BEGIN").state());
            assertEquals(TxState.IDLE, b.query("SELECT").state());
            assertEquals(TxState.ACTIVE, a.query("SELECT").state());
        }
    }

    @Test
    void droppedClientClosesOnlyItsOwnSession() throws Exception {
        try (TestClient a = connect();
                TestClient b = connect()) {
            a.query("BEGIN");
            a.socket.close();

            FakeSession first = processor.sessions.get(0);
            assertTrue(first.closed.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            assertEquals(Session.TxState.ACTIVE, first.stateWhenClosed);

            assertEquals(MessageType.RESULT, b.query("SELECT").messages.getFirst().type());
            assertEquals(1, processor.sessions.get(1).closed.getCount());
        }
    }

    @Test
    void shutdownRequestClosesEveryConnectionAndSession() throws Exception {
        try (TestClient a = connect();
                TestClient b = connect()) {
            b.query("BEGIN");

            a.writer.write(Message.of(MessageType.SHUTDOWN));
            assertEquals(MessageType.READY, a.reply().messages.getLast().type());

            assertEquals(MessageType.CLOSING, b.reader.read().type());
            assertNull(b.reader.read());
            assertTrue(server.awaitStopped(WAIT));
            for (FakeSession session : processor.sessions) {
                assertEquals(0, session.closed.getCount());
            }
            assertThrows(ConnectException.class, this::connect);
        }
    }

    @Test
    void failedStatementsAnswerWithErrorAndKeepTheConnection() throws IOException {
        try (TestClient a = connect()) {
            Reply rejected = a.query("reject");
            assertEquals(MessageType.ERROR, rejected.messages.getFirst().type());
            assertTrue(rejected.messages.getFirst().payload().contains(FakeSession.REJECTION));

            Reply crashed = a.query("crash");
            assertEquals(MessageType.ERROR, crashed.messages.getFirst().type());
            assertFalse(crashed.messages.getFirst().payload().contains(FakeSession.SECRET));

            assertEquals(MessageType.RESULT, a.query("SELECT").messages.getFirst().type());
        }
    }

    static Stream<Arguments> internalFailures() {
        return Stream.of(
                Arguments.of(
                        "RafenDB exception in the cause chain",
                        new IllegalStateException(
                                FakeSession.SECRET, new ConcurrencyInternalException("y")),
                        Component.CCM),
                Arguments.of(
                        "JDK and common frames skipped, mock frame owns it",
                        thrownAt(
                                "java.util.Objects",
                                "org.rafendb.common.query.QueryResult$Rows",
                                "org.rafendb.mock.storage.MockStorageManager",
                                "org.rafendb.processor.session.SessionImpl"),
                        Component.SM),
                Arguments.of(
                        "topmost component frame wins over its callers",
                        thrownAt(
                                "org.rafendb.optimizer.binder.QueryBinder",
                                "org.rafendb.processor.session.SessionImpl",
                                "org.rafendb.server.ClientConnection"),
                        Component.QO),
                Arguments.of(
                        "no component frame",
                        thrownAt("java.util.HashMap", "org.rafendb.server.ClientConnection"),
                        Component.GENERAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("internalFailures")
    void internalErrorNamesTheComponentToBlame(
            String why, RuntimeException failure, Component expected) throws IOException {
        processor.crash = failure;
        try (TestClient a = connect()) {
            Message error = a.query("crash").messages.getFirst();
            assertEquals(MessageType.ERROR, error.type());
            assertTrue(error.payload().contains(expected.displayName()));
        }
    }

    /** A failure whose stack trace has one frame per class, topmost first. */
    private static RuntimeException thrownAt(String... classes) {
        RuntimeException failure = new NullPointerException(FakeSession.SECRET);
        StackTraceElement[] frames = new StackTraceElement[classes.length];
        for (int i = 0; i < classes.length; i++) {
            frames[i] = new StackTraceElement(classes[i], "m", null, -1);
        }
        failure.setStackTrace(frames);
        return failure;
    }

    private TestClient connect() throws IOException {
        return new TestClient(new Socket("127.0.0.1", server.port()));
    }

    /** The messages of one reply; the last one is READY. */
    private record Reply(List<Message> messages) {
        TxState state() throws IOException {
            return TxState.fromCode(messages.getLast().payload());
        }
    }

    private static final class TestClient implements AutoCloseable {
        final Socket socket;
        final MessageReader reader;
        final MessageWriter writer;
        final String sessionId;

        TestClient(Socket socket) throws IOException {
            this.socket = socket;
            this.reader = new MessageReader(socket.getInputStream());
            this.writer = new MessageWriter(socket.getOutputStream());
            Message hello = reader.read();
            assertEquals(MessageType.HELLO, hello.type());
            this.sessionId =
                    hello.payload()
                            .lines()
                            .filter(l -> l.startsWith("session="))
                            .findFirst()
                            .orElseThrow();
        }

        Reply query(String sql) throws IOException {
            writer.write(new Message(MessageType.QUERY, sql));
            return reply();
        }

        Reply reply() throws IOException {
            List<Message> messages = new ArrayList<>();
            Message message;
            do {
                message = reader.read();
                messages.add(message);
            } while (message.type() != MessageType.READY);
            return new Reply(messages);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /**
     * Hands out {@link FakeSession}s and keeps them, in opening order. {@link #crash} is what
     * their {@code crash} statement throws.
     */
    private static final class RecordingProcessor implements QueryProcessor {
        final List<FakeSession> sessions = new CopyOnWriteArrayList<>();
        volatile RuntimeException crash = new IllegalStateException(FakeSession.SECRET);

        @Override
        public Session openSession() {
            FakeSession session = new FakeSession(this);
            sessions.add(session);
            return session;
        }
    }

    /**
     * BEGIN/COMMIT track the state, {@code reject} fails as a client error, {@code crash} fails as
     * a bug with the processor's {@code crash} failure, anything else returns no rows.
     */
    private static final class FakeSession implements Session {
        static final String REJECTION = "test rejection";
        static final String SECRET = "test internal detail";

        final RecordingProcessor owner;
        final CountDownLatch closed = new CountDownLatch(1);
        volatile TxState state = TxState.IDLE;
        volatile Session.TxState stateWhenClosed;

        FakeSession(RecordingProcessor owner) {
            this.owner = owner;
        }

        @Override
        public QueryResult execute(String sql) {
            return switch (sql) {
                case "BEGIN" -> {
                    state = TxState.ACTIVE;
                    yield new QueryResult.Status(sql);
                }
                case "COMMIT" -> {
                    state = TxState.IDLE;
                    yield new QueryResult.Status(sql);
                }
                case "reject" -> throw new ProcessorRuntimeException(REJECTION);
                case "crash" -> throw owner.crash;
                default -> new QueryResult.Rows(List.of("a"), List.of());
            };
        }

        @Override
        public Session.TxState transactionState() {
            return state == TxState.ACTIVE ? Session.TxState.ACTIVE : Session.TxState.IDLE;
        }

        @Override
        public void close() {
            stateWhenClosed = transactionState();
            state = TxState.IDLE;
            closed.countDown();
        }
    }
}
