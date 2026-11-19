package org.rafendb.client;

import java.io.IOException;
import java.io.PrintStream;
import java.net.ProtocolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.rafendb.client.terminal.LineEditor;
import org.rafendb.client.terminal.ScriptInput;
import org.rafendb.engine.Flags;
import org.rafendb.net.Message;
import org.rafendb.net.MessageType;
import org.rafendb.net.TxState;

/**
 * The read-eval-print loop of the client (QP-01). Reads lines, splits them into statements with
 * {@link StatementSplitter}, sends each one and prints its reply; lines starting with {@code \} are
 * meta-commands handled here.
 *
 * <p>A lost connection never ends an interactive client: it prints why, warns if a transaction
 * was open, and waits at the {@code ?>} prompt until {@code \connect}. A non-interactive run (a
 * file, a command or piped input) stops with exit code 2 instead.
 *
 * <p>Exit codes of {@link #run}: 0 on success, 1 if a non-interactive run had a failed statement
 * or command, 2 if it lost the connection.
 */
final class Repl {
    private static final Set<String> QUIT = Set.of("\\q", "\\quit", "\\exit", "quit", "exit");
    private static final Set<String> HELP = Set.of("\\?", "\\h", "\\help", "help");
    private static final int MAX_FILE_DEPTH = 16;
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    private final boolean interactive;
    private final boolean echo;
    private final boolean color;
    private final PrintStream out;
    private final PrintStream err;
    private String host;
    private int port;
    private volatile ServerLink link;
    private volatile TxState state = TxState.IDLE;
    private LineEditor terminal;
    private boolean timing;
    private boolean failed;
    private boolean quit;
    private int depth;

    /** Ends a non-interactive run whose connection is gone. */
    private static final class Disconnected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Disconnected() {
            super(null, null, false, false);
        }
    }

    /**
     * Creates the loop; nothing is connected until {@link #open()}.
     *
     * @param host the server host
     * @param port the server port
     * @param interactive whether a person types at a terminal
     * @param echo whether to print each statement before running it
     * @param out where results and notices go
     * @param err where errors go
     */
    Repl(
            String host,
            int port,
            boolean interactive,
            boolean echo,
            PrintStream out,
            PrintStream err) {
        this.host = host;
        this.port = port;
        this.interactive = interactive;
        this.echo = echo;
        this.color = interactive;
        this.out = out;
        this.err = err;
    }

    /**
     * Connects to the server.
     *
     * @return whether it worked; if not, the reason was printed
     */
    boolean open() {
        try {
            link = ServerLink.open(host, port, this::endedWhileIdle);
            return true;
        } catch (IOException e) {
            error("Could not connect to " + host + ":" + port + ". Is the server running?");
            return false;
        }
    }

    /** Prints the greeting of an interactive client. */
    void greet() {
        printConnected(link);
        out.println(
                "Type \\?, \\h, or \\help for help, \\q \\quit \\exit to quit.\nEnd statements with"
                        + " ;\n");
    }

    /**
     * Runs until the input ends or the user quits, then disconnects.
     *
     * @param input where lines come from
     * @return the exit code
     */
    int run(LineEditor input) {
        terminal = input;
        try {
            runInput(input, true);
        } catch (Disconnected e) {
            return 2;
        } finally {
            ServerLink current = link;
            if (current != null) {
                current.close();
            }
        }
        return interactive || !failed ? 0 : 1;
    }

    private void runInput(LineEditor input, boolean top) {
        StringBuilder buffer = new StringBuilder();
        while (!quit) {
            String line;
            try {
                line = input.readLine(prompt(buffer));
            } catch (IOException e) {
                error("Cannot read input: " + e.getMessage());
                return;
            }
            if (line == null) {
                if (top) {
                    if (interactive) {
                        out.println();
                    }
                    quit = true;
                }
                if (!top || !interactive) {
                    finish(buffer);
                }
                return;
            }
            String trimmed = line.strip();
            if (trimmed.startsWith("\\")
                    || (buffer.isEmpty() && (QUIT.contains(trimmed) || HELP.contains(trimmed)))) {
                meta(trimmed, buffer);
                continue;
            }
            buffer.append(line).append('\n');
            StatementSplitter.Split split = StatementSplitter.split(buffer.toString());
            buffer.setLength(0);
            buffer.append(split.rest());
            for (String sql : split.statements()) {
                if (quit) {
                    return;
                }
                execute(sql);
            }
        }
    }

    /** At the end of a script, runs a last statement that has no {@code ;}, as psql does. */
    private void finish(StringBuilder buffer) {
        StatementSplitter.Split split = StatementSplitter.split(buffer + "\n;");
        switch (split.pending()) {
            case LITERAL -> {
                error("Unterminated quoted string at the end of the input.");
                return;
            }
            case COMMENT -> {
                error("Unterminated /* comment at the end of the input.");
                return;
            }
            default -> {}
        }
        for (String sql : split.statements()) {
            execute(sql);
        }
    }

    private String prompt(StringBuilder buffer) {
        StatementSplitter.Pending pending =
                buffer.isEmpty()
                        ? StatementSplitter.Pending.NONE
                        : StatementSplitter.split(buffer.toString()).pending();
        return switch (pending) {
            case LITERAL -> "rafendb'> ";
            case COMMENT -> "rafendb/*> ";
            case STATEMENT -> "rafendb-> ";
            case NONE -> {
                if (!connected()) {
                    yield "?> ";
                }
                yield switch (state) {
                    case IDLE -> "rafendb=> ";
                    case ACTIVE -> "rafendb*> ";
                    case FAILED -> "rafendb!> ";
                };
            }
        };
    }

    private void execute(String sql) {
        if (echo) {
            out.println(sql);
        }
        if (!connected()) {
            notConnected();
            return;
        }
        long start = System.nanoTime();
        printReply(link.exchange(new Message(MessageType.QUERY, sql), false), false);
        if (timing) {
            out.printf(Locale.ROOT, "Time: %.3f ms%n", (System.nanoTime() - start) / 1e6);
        }
    }

    /**
     * Prints a reply. If it ends the connection, reports that and drops the link.
     *
     * @param expectedEnd whether the end was asked for, as with {@code \shutdown}
     */
    private void printReply(List<Message> reply, boolean expectedEnd) {
        StringBuilder result = new StringBuilder();
        for (Message message : reply) {
            if (ServerLink.isEnd(message)) {
                if (!result.isEmpty()) {
                    out.println(result);
                }
                ended(message, expectedEnd);
                return;
            }
            switch (message.type()) {
                case RESULT -> result.append(message.payload());
                case NOTICE -> out.println(message.payload());
                case ERROR -> error(message.payload());
                case READY -> {
                    if (!result.isEmpty()) {
                        out.println(result);
                        result.setLength(0);
                    }
                    try {
                        state = TxState.fromCode(message.payload());
                    } catch (ProtocolException e) {
                        error("The server sent an unknown transaction state.");
                    }
                }
                default -> error("The server sent an unexpected " + message.type() + ".");
            }
        }
    }

    /** The connection ended during a request: say why, warn about the transaction, drop it. */
    private void ended(Message end, boolean expected) {
        out.println(end == ServerLink.LOST ? "Connection to server lost." : end.payload());
        if (state != TxState.IDLE) {
            out.println("Your open transaction was rolled back.");
        }
        link.close();
        link = null;
        state = TxState.IDLE;
        if (interactive) {
            out.println("Not connected to server.");
        } else if (!expected) {
            throw new Disconnected();
        }
    }

    /** The connection ended while no request was waiting; runs on the reader thread. */
    private void endedWhileIdle(Message end) {
        synchronized (out) {
            if (interactive) {
                out.println(); // leave the prompt line the user sits at
            }
            out.println(end == ServerLink.LOST ? "Connection to server lost." : end.payload());
            if (state != TxState.IDLE) {
                out.println("Your open transaction was rolled back.");
            }
            if (interactive) {
                out.println("Not connected to server.");
                out.print("?> ");
                out.flush();
            }
        }
    }

    /**
     * Returns whether a link is open, first dropping one the reader thread found ended.
     *
     * @throws Disconnected in a non-interactive run whose connection ended meanwhile
     */
    private boolean connected() {
        ServerLink current = link;
        if (current != null && current.isEnded()) {
            current.close();
            link = null;
            state = TxState.IDLE;
            if (!interactive) {
                throw new Disconnected();
            }
        }
        return link != null;
    }

    private void notConnected() {
        error("Not connected to server. Type \\connect to reconnect.");
        if (!interactive) {
            throw new Disconnected();
        }
    }

    private void meta(String line, StringBuilder buffer) {
        String[] parts = line.split("\\s+", 2);
        String command = parts[0];
        String argument = parts.length > 1 ? parts[1].strip() : "";
        if (QUIT.contains(command)) {
            quit = true;
        } else if (HELP.contains(command)) {
            help();
        } else {
            switch (command) {
                case "\\r" -> buffer.setLength(0);
                case "\\f" -> runFile(argument);
                case "\\timing" -> timing(argument);
                case "\\info" -> info();
                case "\\clear" -> {
                    out.print("\u001B[H\u001B[2J");
                    out.flush();
                }
                case "\\shutdown" -> shutdown();
                case "\\connect", "\\c" -> connect(argument);
                default -> error("Unknown command: " + command + ". Type \\? for help.");
            }
        }
    }

    private void help() {
        out.println("End SQL statements with ; and may span several lines. Type:");
        out.println("  \\?, \\h, \\help        show this help");
        out.println("  \\q, \\quit, \\exit     quit (also Ctrl+D)");
        out.println("  \\r                   clear the buffer and all statement typed so far");
        out.println("  \\f <file>            run the statements in a file");
        out.println("  \\timing [on|off]     show how long each statement takes");
        out.println("  \\info                show the connection");
        out.println("  \\clear               clear the screen");
        out.println("  \\connect [--host=<host>] [--port=<n>]");
        out.println("                       connect again, alias \\c");
        out.println("  \\shutdown            shut the server down for everyone");
    }

    private void runFile(String argument) {
        if (argument.isEmpty()) {
            error("Usage: \\f <file>");
            return;
        }
        if (depth >= MAX_FILE_DEPTH) {
            error("Files call each other too deeply; stopped at " + argument + ".");
            return;
        }
        Path file = Path.of(argument);
        try (ScriptInput input = new ScriptInput(Files.newBufferedReader(file), null)) {
            depth++;
            runInput(input, false);
        } catch (IOException e) {
            error("Cannot read " + file + ": " + e.getMessage());
        } finally {
            depth--;
        }
    }

    private void timing(String argument) {
        switch (argument.toLowerCase(Locale.ROOT)) {
            case "" -> timing = !timing;
            case "on" -> timing = true;
            case "off" -> timing = false;
            default -> {
                error("Usage: \\timing [on|off]");
                return;
            }
        }
        out.println("Timing is " + (timing ? "on." : "off."));
    }

    private void info() {
        if (connected()) {
            printConnected(link);
        } else {
            out.println("Not connected to server. Last server was " + host + ":" + port + ".");
        }
    }

    private void shutdown() {
        if (!connected()) {
            notConnected();
            return;
        }
        if (interactive) {
            String answer;
            try {
                answer = terminal.readLine("Shut down the server for all sessions? [y/N] ");
            } catch (IOException e) {
                answer = null;
            }
            if (answer == null
                    || !Set.of("y", "yes").contains(answer.strip().toLowerCase(Locale.ROOT))) {
                out.println("Shutdown cancelled.");
                return;
            }
        }
        printReply(link.exchange(Message.of(MessageType.SHUTDOWN), true), true);
    }

    private void connect(String argument) {
        String newHost;
        int newPort;
        try {
            Flags flags = Flags.parse(argument.isEmpty() ? new String[0] : argument.split("\\s+"));
            newHost = flags.value("host", host);
            newPort = flags.intValue("port", port);
            flags.rejectUnknown();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        if (connected() && link.host.equals(newHost) && link.port == newPort) {
            // Reconnecting would only end this session and open a new one.
            out.println(
                    "Already connected to "
                            + newHost
                            + ":"
                            + newPort
                            + " with session ID #"
                            + link.session
                            + ".");
            return;
        }
        ServerLink fresh;
        try {
            fresh = ServerLink.open(newHost, newPort, this::endedWhileIdle);
        } catch (IOException e) {
            error("Could not connect to " + newHost + ":" + newPort + ". Is the server running?");
            if (connected()) {
                out.println("Previous connection kept.");
            }
            return;
        }
        if (connected()) {
            if (state != TxState.IDLE) {
                out.println("Your open transaction was aborted.");
            }
            link.close();
        }
        link = fresh;
        state = TxState.IDLE;
        host = newHost;
        port = newPort;
        printConnected(fresh);
    }

    private void printConnected(ServerLink current) {
        out.println(
                "Connected to RafenDB Server "
                        + current.host
                        + ":"
                        + current.port
                        + " with session ID #"
                        + current.session
                        + ".");
        out.println(
                "Server version "
                        + current.version
                        + ". Concurrency protocol "
                        + current.protocol
                        + ".");
    }

    private void error(String text) {
        failed = true;
        err.println(color ? RED + text + RESET : text);
    }
}
