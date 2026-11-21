package org.rafendb.server;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.rafendb.api.concurrency.ProtocolType;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.engine.Engine;
import org.rafendb.engine.EngineConfig;
import org.rafendb.engine.Flags;
import org.rafendb.engine.Version;

/**
 * The multi-client server (QP-02) and its entry point. Every accepted connection gets its own
 * virtual thread and its own {@link org.rafendb.api.processor.Session} from the one shared {@link
 * QueryProcessor}, so clients run concurrently on the same database and one client leaving never
 * disturbs another.
 *
 * <p>{@link #main} parses the flags, builds the engine and runs the server until it is shut down,
 * either by a client's {@code \shutdown} or by Ctrl+C / SIGTERM, which run the same graceful
 * {@link #shutdown(String)} through a JVM shutdown hook.
 *
 * <p>Thread-safe. The monitor of this object guards {@link #stopping} and the step that registers
 * a new connection; it is never held while waiting for connections to end.
 */
public final class ServerMain {
    private final ServerConfig config;
    private final QueryProcessor processor;
    private final ProtocolType protocol;
    private final ServerSocket listener;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, ClientConnection> connections = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private boolean stopping;

    /**
     * Starts the server.
     *
     * @param args the command-line flags; see {@code --help}
     */
    public static void main(String[] args) {
        ServerConfig config;
        EngineConfig engineConfig;
        try {
            Flags flags = Flags.parse(args);
            if (flags.has("version")) {
                System.out.println(Version.version());
                return;
            }
            if (flags.has("help")) {
                help();
                return;
            }
            ServerConfig defaults = ServerConfig.defaults();
            String timeout = flags.value("timeout", null);
            config =
                    new ServerConfig(
                            flags.intValue("port", defaults.port()),
                            timeout == null ? defaults.shutdownTimeout() : seconds(timeout));
            engineConfig = EngineConfig.fromFlags(flags).withDrainTimeout(config.shutdownTimeout());
            flags.rejectUnknown();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            help();
            System.exit(1);
            return;
        }

        System.out.println(Version.banner("Server"));
        Engine engine;
        try {
            engine = Engine.start(engineConfig);
        } catch (Engine.StartupException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        ServerMain server;
        try {
            server = new ServerMain(config, engine.processor(), engine.concurrencyProtocol());
        } catch (BindException e) {
            engine.close();
            System.err.println("Port " + config.port() + " is already in use.");
            System.exit(1);
            return;
        } catch (IOException e) {
            engine.close();
            System.err.println("Cannot listen on port " + config.port() + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        // Sessions close first (each aborts its own transaction), then the engine.
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    server.shutdown("interrupted");
                                    engine.close();
                                },
                                "rafendb-shutdown-hook"));
        server.start();
        System.out.println(
                "Listening on port " + server.port() + ". Press Ctrl+C to stop the server.");
        server.awaitStopped();
        engine.close(); // after a client's \shutdown; idempotent with the hook
    }

    /**
     * Creates the server and binds its port; clients are accepted after {@link #start()}.
     *
     * @param config the network and lifecycle settings
     * @param processor the query processor every session is opened from
     * @param protocol the concurrency protocol, announced to clients
     * @throws BindException if the port is in use
     * @throws IOException if the port cannot be opened
     */
    ServerMain(ServerConfig config, QueryProcessor processor, ProtocolType protocol)
            throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        this.processor = Objects.requireNonNull(processor, "processor");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        this.listener = new ServerSocket(config.port());
    }

    /** Starts accepting clients in the background. Call once. */
    void start() {
        Thread.ofVirtual().name("rafendb-accept").start(this::acceptLoop);
    }

    /**
     * Returns the port listened on, which differs from the configured one when that was 0.
     *
     * @return the local port
     */
    int port() {
        return listener.getLocalPort();
    }

    /**
     * Stops the server gracefully: refuse new clients, tell idle clients the server is closing,
     * let running statements finish within the shutdown timeout, and wait until every session is
     * closed (which aborts its open transaction). Connections still running after the timeout are
     * cut. Idempotent; a second caller waits for the first to finish.
     *
     * @param why why the server stops, for the server log only
     */
    void shutdown(String why) {
        List<ClientConnection> open;
        synchronized (this) {
            if (stopping) {
                open = null;
            } else {
                stopping = true;
                closeQuietly(listener);
                open = List.copyOf(connections.values());
            }
        }
        if (open == null) {
            awaitStopped(config.shutdownTimeout().plusSeconds(1));
            return;
        }
        log("Shutting down (" + why + ").");
        for (ClientConnection connection : open) {
            connection.requestClose();
        }
        long deadline = System.nanoTime() + config.shutdownTimeout().toNanos();
        for (ClientConnection connection : open) {
            Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            if (!connection.awaitEnd(left)) {
                log(connection.tag() + " did not finish in time; connection cut.");
                connection.forceClose();
            }
        }
        log("Server stopped.");
        stopped.countDown();
    }

    /** Waits until {@link #shutdown(String)} has finished. */
    void awaitStopped() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits until {@link #shutdown(String)} has finished, at most {@code timeout}.
     *
     * @param timeout the longest time to wait
     * @return whether the server has stopped
     */
    boolean awaitStopped(Duration timeout) {
        try {
            return stopped.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Starts a shutdown on another thread, so a connection can ask for it and keep serving. */
    void shutdownAsync(String why) {
        Thread.ofVirtual().name("rafendb-shutdown").start(() -> shutdown(why));
    }

    QueryProcessor processor() {
        return processor;
    }

    ProtocolType protocol() {
        return protocol;
    }

    void remove(ClientConnection connection) {
        connections.remove(connection.id());
    }

    void log(String line) {
        System.out.println(line);
    }

    private void acceptLoop() {
        while (true) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (!isStopping()) {
                    System.err.println("Stopped accepting clients: " + e.getMessage());
                }
                return;
            }
            admit(socket);
        }
    }

    private void admit(Socket socket) {
        ClientConnection connection;
        try {
            socket.setKeepAlive(true);
            connection = new ClientConnection(ids.incrementAndGet(), socket, this);
        } catch (IOException e) {
            closeQuietly(socket);
            System.err.println("Could not set up a client connection: " + e.getMessage());
            return;
        }
        if (!register(connection)) {
            closeQuietly(socket);
        }
    }

    /**
     * Registers and starts a connection, atomically with the snapshot {@link #shutdown} takes.
     *
     * @return false if the server is stopping and the connection was not started
     */
    private synchronized boolean register(ClientConnection connection) {
        if (stopping) {
            return false;
        }
        connections.put(connection.id(), connection);
        connection.start();
        return true;
    }

    private synchronized boolean isStopping() {
        return stopping;
    }

    static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception _) {
        }
    }

    private static void help() {
        ServerConfig defaults = ServerConfig.defaults();
        System.out.println("Usage: server [options]");
        System.out.println("  --port=<n>     port to listen on (default " + defaults.port() + ")");
        System.out.println(
                "  --timeout=<s>  shutdown waiting timeout in seconds (default "
                        + defaults.shutdownTimeout().toSeconds()
                        + ")");
        EngineConfig engine = EngineConfig.defaults();
        System.out.println("  --dir=<path>   data directory (default " + engine.dir() + ")");
        System.out.println("  --seed         load the sample database into an empty database");
        System.out.println("  --protocol=lock|timestamp  concurrency protocol (default lock)");
        System.out.println(
                "  --mock=<list>  mock components, any of qp,sm,ccm,frm; mocking sm, ccm,"
                        + " or frm uses a temporary directory instead of --dir");
        System.out.println(
                "  --frames=<n>   buffer pool size in pages (default "
                        + engine.bufferFrames()
                        + ")");
        System.out.println("  --help         show this help");
        System.out.println("  --version      show the version");
    }

    /** Parses a number of seconds, with or without a trailing {@code s}. */
    private static Duration seconds(String text) {
        String digits = text.strip();
        if (digits.endsWith("s")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        try {
            return Duration.ofSeconds(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--timeout must be a number of seconds: " + text);
        }
    }
}
