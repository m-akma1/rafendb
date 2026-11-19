package org.rafendb.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.rafendb.engine.Flags;
import org.rafendb.engine.Version;
import org.rafendb.net.Message;
import org.rafendb.net.MessageReader;
import org.rafendb.net.MessageType;
import org.rafendb.net.MessageWriter;
import org.rafendb.net.TxState;

/**
 * Client entry point (QP-01). For now one line is one statement; statement splitting on {@code ;},
 * the line editor and the remaining meta-commands follow.
 *
 * <p>Exit codes: 0 after {@code \q} or a server shutdown, 1 for bad flags, 2 when the server
 * cannot be reached or the connection is lost.
 */
public final class ClientMain {
    private static final Set<String> QUIT = Set.of("\\q", "\\quit", "\\exit", "quit", "exit");

    private ClientMain() {}

    /**
     * Starts the client.
     *
     * @param args the command-line flags; see {@code --help}
     */
    public static void main(String[] args) {
        String host;
        int port;
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
            host = flags.value("host", "127.0.0.1");
            port = flags.intValue("port", 3140);
            flags.rejectUnknown();
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            help();
            System.exit(1);
            return;
        }
        System.out.println(Version.banner("Client"));
        System.exit(run(host, port));
    }

    private static int run(String host, int port) {
        try (Socket socket = new Socket(host, port)) {
            MessageReader in = new MessageReader(socket.getInputStream());
            MessageWriter out = new MessageWriter(socket.getOutputStream());
            Map<String, String> hello = hello(in.read());
            System.out.println(
                    "Connected to "
                            + host
                            + ":"
                            + port
                            + " with session ID #"
                            + hello.get("session")
                            + ".");
            System.out.println(
                    "Server version "
                            + hello.get("version")
                            + ". Concurrency protocol "
                            + hello.get("protocol")
                            + ".");
            System.out.println(
                    "Type \\?, \\h, or \\help for help, \\q, \\quit, or \\exit to quit.\nEnd statements"
                            + " with ;");
            return loop(in, out);
        } catch (ConnectException e) {
            System.err.println(
                    "Could not connect to " + host + ":" + port + ". Is the server running?");
            return 2;
        } catch (IOException e) {
            System.err.println("Connection to server lost.");
            return 2;
        }
    }

    private static int loop(MessageReader in, MessageWriter out) throws IOException {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
        TxState state = TxState.IDLE;
        while (true) {
            System.out.print(prompt(state));
            System.out.flush();
            String line = stdin.readLine();
            if (line == null || QUIT.contains(line.strip())) {
                if (line == null) {
                    System.out.println();
                }
                out.write(Message.of(MessageType.BYE));
                return 0;
            }
            if (line.isBlank()) {
                continue;
            }
            boolean shutdown = line.strip().equals("\\shutdown");
            out.write(
                    shutdown
                            ? Message.of(MessageType.SHUTDOWN)
                            : new Message(MessageType.QUERY, line.strip()));
            state = printReply(in);
            if (state == null) {
                return 0;
            }
            if (shutdown) {
                // The server closes every connection next; wait for that instead of prompting.
                return printReply(in) == null ? 0 : 2;
            }
        }
    }

    /**
     * Prints one reply up to its READY.
     *
     * @return the transaction state from READY, or null if the server closed the connection
     */
    private static TxState printReply(MessageReader in) throws IOException {
        StringBuilder result = new StringBuilder();
        while (true) {
            Message message = in.read();
            if (message == null) {
                throw new IOException("connection closed");
            }
            switch (message.type()) {
                case RESULT -> result.append(message.payload());
                case NOTICE -> System.out.println(message.payload());
                case ERROR -> System.err.println(message.payload());
                case READY -> {
                    if (!result.isEmpty()) {
                        System.out.println(result);
                    }
                    return TxState.fromCode(message.payload());
                }
                case CLOSING -> {
                    System.out.println(message.payload());
                    return null;
                }
                default -> throw new IOException("unexpected " + message.type() + " from server");
            }
        }
    }

    private static Map<String, String> hello(Message message) throws IOException {
        if (message == null || message.type() != MessageType.HELLO) {
            throw new IOException("server did not greet");
        }
        Map<String, String> fields = new HashMap<>();
        for (String line : message.payload().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                fields.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return fields;
    }

    private static String prompt(TxState state) {
        return switch (state) {
            case IDLE -> "rafendb=> ";
            case ACTIVE -> "rafendb*> ";
            case FAILED -> "rafendb!> ";
        };
    }

    private static void help() {
        System.out.println("Usage: client [options]");
        System.out.println("  --host=<host>  server host (default 127.0.0.1)");
        System.out.println("  --port=<n>     server port (default 3140)");
        System.out.println("  --help         show this help");
        System.out.println("  --version      show the version");
    }
}
