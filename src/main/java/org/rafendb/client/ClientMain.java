package org.rafendb.client;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.rafendb.client.terminal.JLineEditor;
import org.rafendb.client.terminal.LineEditor;
import org.rafendb.client.terminal.ScriptInput;
import org.rafendb.engine.Flags;
import org.rafendb.engine.Version;

/**
 * Client entry point (QP-01). Interactive when stdin is a terminal and neither {@code --file} nor
 * {@code --command} is given; otherwise it runs the statements without prompts and reports
 * through its exit code.
 *
 * <p>Exit codes: 0 on success, 1 for bad flags or a failed statement in a non-interactive run, 2
 * when the server cannot be reached or a non-interactive run loses the connection.
 */
public final class ClientMain {
    private static final int DEFAULT_PORT = 3140;
    private static final int DEFAULT_HISTORY_SIZE = 500;

    private ClientMain() {}

    /**
     * Starts the client.
     *
     * @param args the command-line flags; see {@code --help}
     */
    public static void main(String[] args) {
        String host;
        int port;
        String file;
        String command;
        boolean echo;
        ClientHome home;
        boolean timing;
        int historySize;
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
            home = ClientHome.resolve(flags.value("home", null));
            host = flags.value("host", home.value("host", "127.0.0.1"));
            port = flags.intValue("port", home.intValue("port", DEFAULT_PORT));
            timing = home.onOff("timing", false);
            historySize = home.intValue("history.size", DEFAULT_HISTORY_SIZE);
            file = flags.value("file", null);
            command = flags.value("command", null);
            echo = flags.has("echo");
            flags.rejectUnknown();
            if (file != null && command != null) {
                throw new IllegalArgumentException("use either --file or --command, not both");
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            help();
            System.exit(1);
            return;
        }

        Console console = System.console();
        boolean interactive =
                file == null && command == null && console != null && console.isTerminal();
        Repl repl =
                new Repl(host, port, interactive, echo, timing, home.dir(), System.out, System.err);
        LineEditor input;
        try {
            input = interactive ? terminal(home, historySize, repl) : script(file, command);
        } catch (IOException e) {
            System.err.println(
                    (interactive ? "Cannot open the terminal: " : "Cannot read " + file + ": ")
                            + e.getMessage());
            System.exit(1);
            return;
        }

        if (interactive) {
            System.out.println(Version.banner("Client"));
        }
        if (!repl.open()) {
            System.exit(2);
            return;
        }
        if (interactive) {
            repl.greet();
        }
        System.exit(repl.run(input));
    }

    /** The line editor of an interactive client, with history kept in the client home. */
    private static LineEditor terminal(ClientHome home, int historySize, Repl repl)
            throws IOException {
        Path historyFile = null;
        if (historySize > 0) {
            historyFile = home.historyFile();
            if (historyFile == null) {
                System.err.println(
                        "Cannot create " + home.dir() + "; history is kept for this session only.");
            }
        }
        return new JLineEditor(Repl::continuation, historyFile, historySize, repl::interrupt);
    }

    /** The input of a non-interactive run: a file, a command, or piped stdin. */
    private static LineEditor script(String file, String command) throws IOException {
        if (file != null) {
            return new ScriptInput(Files.newBufferedReader(Path.of(file)));
        }
        if (command != null) {
            return new ScriptInput(new BufferedReader(new StringReader(command)));
        }
        return new ScriptInput(new BufferedReader(new InputStreamReader(System.in)));
    }

    private static void help() {
        System.out.println("Usage: scripts/run.sh client [options]");
        System.out.println("  --host=<host>    server host (default 127.0.0.1)");
        System.out.println("  --port=<n>       server port (default " + DEFAULT_PORT + ")");
        System.out.println("  --file=<file>    run the statements in a file, then exit");
        System.out.println("  --command=<sql>  run the given statements, then exit");
        System.out.println("  --echo           print each statement before its result");
        System.out.println("  --home=<dir>     client directory for history and client.properties");
        System.out.println("  --help           show this help");
        System.out.println("  --version        show the version");
    }
}
