package org.rafendb.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.rafendb.common.Component;
import org.rafendb.common.error.RafenDBInternalException;
import org.rafendb.common.error.RafenDBRuntimeException;
import org.rafendb.engine.Version;

/**
 * Base class for component drivers. A driver extends it, registers its commands in {@link
 * #setup()}, and gets the rest: flags, a prompt, running files of commands, PASS/FAIL checks, error
 * printing and an exit code.
 *
 * <p><b>Run.</b> {@link #run} prints the banner, parses the flags, prints which components are
 * mocked, calls {@link #setup()}, then runs the {@code --run} file or reads commands at the prompt
 * until {@code quit}, {@code exit} or end of input, and finally calls {@link #close()}. The exit code
 * is 0 when no check failed and no error was printed, and 1 otherwise.
 *
 * <p><b>Flags.</b> Only {@code --name=value} and bare {@code --name}. Built in: {@code --mock=<list>}
 * (short names {@code qp,qo,sm,ccm,frm} in any case, or {@code all}), {@code --run=<file>} and
 * {@code --help}. A driver reads its own flags with {@link #option} and {@link #flag} inside {@link
 * #setup()}; any flag nobody read by the end of setup is reported as an unknown option.
 *
 * <p><b>Lines.</b> Blank lines and lines starting with {@code #} or {@code --} are skipped. Words
 * are separated by spaces, and double quotes keep spaces inside one word. A line whose first word is
 * a command runs it; any other line goes to {@link #fallback}. Built-in commands: {@code help},
 * {@code run <file>} (prints {@code RESULT: X passed, Y failed} for that file) and {@code quit}.
 *
 * <p><b>Errors.</b> An exception from a command is printed and counts as one failure, and the
 * driver goes on with the next line. RafenDB exceptions print their own message, such as {@code
 * Runtime error at Storage Manager: ...}; any other exception prints {@code Unexpected error:
 * <type>: <message>}.
 */
public abstract class Driver {
    private static final Set<String> BUILT_INS = Set.of("help", "run", "quit", "exit");

    /** A driver command. */
    @FunctionalInterface
    public interface Command {
        /**
         * Runs the command.
         *
         * @param args the words after the command name, with double quotes removed
         * @param rest the text after the command name exactly as typed, for SQL or free text
         * @throws Exception any failure; it is printed and counted, and the driver goes on
         */
        void execute(List<String> args, String rest) throws Exception;
    }

    private record Entry(String usage, String help, Command action) {}

    private final Component component;
    private final String title;
    private final String prompt;
    private final Map<String, Entry> commands = new LinkedHashMap<>();
    private final Map<String, String> options = new HashMap<>();
    private final Set<String> readOptions = new HashSet<>();
    private final Set<Component> mocks = EnumSet.noneOf(Component.class);
    private final Object counterLock = new Object();
    private PrintStream out = System.out;
    private int passed;
    private int failed;
    private boolean quit;
    private boolean inFile;

    /**
     * Creates the driver of a component. The banner title is derived from it, such as {@code
     * "Storage Manager Driver"}, and so is the prompt, such as {@code "sm> "}. The component under
     * test cannot be named in {@code --mock}.
     *
     * @param component the component this driver tests
     * @throws IllegalArgumentException if the component is {@link Component#GENERAL}
     */
    protected Driver(Component component) {
        this.component = Objects.requireNonNull(component, "component");
        if (component == Component.GENERAL) {
            throw new IllegalArgumentException("a driver tests one component, not GENERAL");
        }
        this.title = component.displayName() + " Driver";
        this.prompt = component.shortName().toLowerCase(Locale.ROOT) + "> ";
    }

    /**
     * Reads the driver's flags, builds its components (real, or mock where {@link #mocked} says so)
     * and registers its commands with {@link #command}. If it throws, the error is printed, {@link
     * #close()} is called and the driver exits with 1.
     *
     * @throws Exception if the driver cannot start
     */
    protected abstract void setup() throws Exception;

    /**
     * Releases what {@link #setup()} built. Called exactly once at the end of every run, even after
     * a failed setup, so it must cope with fields that were never set. Does nothing by default.
     *
     * @throws Exception if releasing fails; it is printed and counted as a failure
     */
    protected void close() throws Exception {}

    /**
     * Handles a line whose first word is no command, such as an SQL statement. The default throws
     * {@link DriverException} "unknown command".
     *
     * @param line the whole line, trimmed, exactly as typed
     * @throws Exception any failure; it is printed and counted, and the driver goes on
     */
    protected void fallback(String line) throws Exception {
        throw new DriverException(
                "unknown command: " + split(line).getFirst() + " (hint: type help)");
    }

    /**
     * Prints the help: the usage line, the built-in flags, the driver's commands and the built-in
     * commands. Shown by {@code --help}, by the {@code help} command and after a flag error. To add
     * the driver's own flags or notes, override it, call {@code super.help()} first, then print with
     * {@link #out()}. With {@code --help} it runs before {@link #setup()}, so only the built-in
     * commands are listed there.
     */
    protected void help() {
        out.println("Usage: " + title + " [options]");
        out.println("  --mock=<list>   use mocks for other components: qp,qo,sm,ccm,frm or all");
        out.println("  --run=<file>    run a file of commands, then exit");
        out.println("  --help          show this help");
        List<String[]> rows = new ArrayList<>();
        commands.values().forEach(e -> rows.add(new String[] {e.usage(), e.help()}));
        int driverRows = rows.size();
        rows.add(new String[] {"help", "show this help"});
        rows.add(new String[] {"run <file>", "run a file of commands"});
        rows.add(new String[] {"quit", "stop (also: exit)"});
        int width = rows.stream().mapToInt(r -> r[0].length()).max().orElse(0);
        for (int i = 0; i < rows.size(); i++) {
            if (i == 0 && driverRows > 0) {
                out.println("Commands:");
            }
            if (i == driverRows) {
                out.println("Built-in commands:");
            }
            out.printf("  %-" + width + "s  %s%n", rows.get(i)[0], rows.get(i)[1]);
        }
    }

    /**
     * Registers a command. Names are matched ignoring case; {@code help}, {@code run}, {@code quit}
     * and {@code exit} are taken.
     *
     * @param name the first word of the line, without spaces
     * @param usage the synopsis shown by {@code help}, such as {@code "scan <table> [limit]"}
     * @param help one line shown by {@code help}
     * @param action what to run
     * @throws IllegalArgumentException if the name has spaces, is built in or is already registered
     */
    protected final void command(String name, String usage, String help, Command action) {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.isEmpty() || key.chars().anyMatch(Character::isWhitespace) || key.contains("\"")) {
            throw new IllegalArgumentException("bad command name: " + name);
        }
        if (BUILT_INS.contains(key) || commands.containsKey(key)) {
            throw new IllegalArgumentException("command already registered: " + name);
        }
        commands.put(key, new Entry(usage, help, Objects.requireNonNull(action, "action")));
    }

    /**
     * Returns the value of {@code --name=value}. Read flags in {@link #setup()}: a flag nobody read
     * by the end of setup is reported as unknown.
     *
     * @param name the flag name without dashes
     * @return the value, or null if the flag was not given
     * @throws DriverException if the flag was given without a value
     */
    protected final String option(String name) {
        if (!options.containsKey(name)) {
            return null;
        }
        readOptions.add(name);
        String value = options.get(name);
        if (value == null) {
            throw new DriverException("--" + name + " needs a value: --" + name + "=<value>");
        }
        return value;
    }

    /**
     * Returns the value of {@code --name=value}, or a default when the flag was not given.
     *
     * @param name the flag name without dashes
     * @param fallback the value to use when the flag is absent
     * @return the value or the default
     * @throws DriverException if the flag was given without a value
     */
    protected final String option(String name, String fallback) {
        String value = option(name);
        return value != null ? value : fallback;
    }

    /**
     * Returns whether bare {@code --name} was given. Read flags in {@link #setup()}.
     *
     * @param name the flag name without dashes
     * @return true if the flag was given
     * @throws DriverException if the flag was given a value
     */
    protected final boolean flag(String name) {
        if (!options.containsKey(name)) {
            return false;
        }
        readOptions.add(name);
        if (options.get(name) != null) {
            throw new DriverException("--" + name + " takes no value");
        }
        return true;
    }

    /**
     * Returns whether a component was listed in {@code --mock}.
     *
     * @param component the component
     * @return true if the driver should use its mock
     */
    protected final boolean mocked(Component component) {
        return mocks.contains(component);
    }

    /**
     * Records a check that compares two values with {@link Objects#equals} and prints {@code PASS
     * what: expected E, actual A} or the same with {@code FAIL}. Compare lists or strings, not
     * arrays. Safe to call from several threads.
     *
     * @param what a short description
     * @param expected the expected value
     * @param actual the actual value
     * @return whether the values are equal
     */
    protected final boolean check(String what, Object expected, Object actual) {
        boolean ok = Objects.equals(expected, actual);
        record(ok, what + ": expected " + expected + ", actual " + actual);
        return ok;
    }

    /**
     * Records a check on a condition and prints {@code PASS what} or {@code FAIL what}. Safe to call
     * from several threads.
     *
     * @param what a short description
     * @param ok whether the check held
     * @return {@code ok}
     */
    protected final boolean check(String what, boolean ok) {
        record(ok, what);
        return ok;
    }

    /**
     * Returns the stream to print to.
     *
     * @return the output stream of the current run
     */
    protected final PrintStream out() {
        return out;
    }

    /**
     * Runs the driver on the standard streams and exits the JVM with its exit code. Call this from
     * {@code main}.
     *
     * @param args the arguments given to {@code main}
     */
    public final void launch(String[] args) {
        System.exit(run(args, System.in, System.out));
    }

    /**
     * Runs the driver; tests call this directly.
     *
     * @param args the command-line arguments
     * @param in where commands are read from when there is no {@code --run}
     * @param out where everything is printed
     * @return 0 if no check failed and no error was printed, 1 otherwise
     */
    public final int run(String[] args, InputStream in, PrintStream out) {
        reset(out);
        out.println(Version.banner(title));
        String runFile;
        try {
            parseArgs(args);
            if (flag("help")) {
                help();
                return 0;
            }
            runFile = option("run");
            parseMocks(option("mock"));
        } catch (DriverException e) {
            out.println(e.getMessage());
            help();
            return 1;
        }
        out.println("Mocked: " + describeMocks());

        try {
            try {
                setup();
            } catch (Exception e) {
                report(e);
                return 1;
            }
            List<String> unread = new ArrayList<>(options.keySet());
            unread.removeAll(readOptions);
            if (!unread.isEmpty()) {
                unread.sort(null);
                for (String name : unread) {
                    out.println(new DriverException("unknown option: --" + name).getMessage());
                }
                help();
                return 1;
            }
            if (runFile != null) {
                try {
                    runFile(Path.of(runFile));
                } catch (Exception e) {
                    report(e);
                }
            } else {
                readCommands(in);
            }
        } finally {
            try {
                close();
            } catch (Exception e) {
                report(e);
            }
        }
        synchronized (counterLock) {
            return failed == 0 ? 0 : 1;
        }
    }

    private void reset(PrintStream stream) {
        out = Objects.requireNonNull(stream, "out");
        commands.clear();
        options.clear();
        readOptions.clear();
        mocks.clear();
        synchronized (counterLock) {
            passed = 0;
            failed = 0;
        }
        quit = false;
        inFile = false;
    }

    private void parseArgs(String[] args) {
        for (String arg : args) {
            int eq = arg.indexOf('=');
            String name =
                    !arg.startsWith("--") ? "" : eq < 0 ? arg.substring(2) : arg.substring(2, eq);
            if (name.isEmpty()) {
                throw new DriverException(
                        "unexpected argument: " + arg + " (flags are --name=value or --name)");
            }
            if (options.containsKey(name)) {
                throw new DriverException("--" + name + " given more than once");
            }
            options.put(name, eq < 0 ? null : arg.substring(eq + 1));
        }
    }

    private void parseMocks(String list) {
        if (list == null) {
            return;
        }
        for (String part : list.split(",")) {
            String name = part.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (name.equalsIgnoreCase("all")) {
                mocks.addAll(EnumSet.complementOf(EnumSet.of(Component.GENERAL, component)));
                continue;
            }
            Component mock;
            try {
                mock = Component.fromShortName(name);
            } catch (IllegalArgumentException e) {
                mock = Component.GENERAL;
            }
            if (mock == Component.GENERAL) {
                throw new DriverException(
                        "unknown component in --mock: "
                                + name
                                + " (use qp, qo, sm, ccm, frm or all)");
            }
            if (mock == component) {
                throw new DriverException(
                        "--mock cannot be the driver's own component: "
                                + component.shortName().toLowerCase(Locale.ROOT));
            }
            mocks.add(mock);
        }
    }

    private String describeMocks() {
        if (mocks.isEmpty()) {
            return "none";
        }
        return mocks.stream().map(Component::displayName).collect(Collectors.joining(", "));
    }

    private void readCommands(InputStream in) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        try {
            while (!quit) {
                out.print(prompt);
                out.flush();
                String line = reader.readLine();
                if (line == null) {
                    out.println();
                    return;
                }
                execute(line);
            }
        } catch (IOException e) {
            report(e);
        }
    }

    /** Runs one line; never throws, a failure is printed and counted. */
    private void execute(String line) {
        String text = line.strip();
        if (skipped(text)) {
            return;
        }
        try {
            dispatch(text);
        } catch (Exception e) {
            report(e);
        }
    }

    private void dispatch(String text) throws Exception {
        List<String> words = split(text);
        String name = words.getFirst().toLowerCase(Locale.ROOT);
        List<String> args = List.copyOf(words.subList(1, words.size()));
        switch (name) {
            case "help" -> help();
            case "quit", "exit" -> quit = true;
            case "run" -> {
                if (inFile) {
                    throw new DriverException("run cannot be used inside a file");
                }
                if (args.size() != 1) {
                    throw new DriverException("usage: run <file>");
                }
                runFile(Path.of(args.getFirst()));
            }
            default -> {
                Entry entry = commands.get(name);
                if (entry == null) {
                    fallback(text);
                } else {
                    entry.action().execute(args, afterFirstWord(text));
                }
            }
        }
    }

    private void runFile(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int passedBefore;
        int failedBefore;
        synchronized (counterLock) {
            passedBefore = passed;
            failedBefore = failed;
        }
        inFile = true;
        try {
            for (String line : lines) {
                if (quit) {
                    break;
                }
                String text = line.strip();
                if (skipped(text)) {
                    continue;
                }
                out.println("> " + text);
                execute(text);
            }
        } finally {
            inFile = false;
        }
        synchronized (counterLock) {
            out.println(
                    "RESULT: "
                            + (passed - passedBefore)
                            + " passed, "
                            + (failed - failedBefore)
                            + " failed");
        }
    }

    private void record(boolean ok, String text) {
        synchronized (counterLock) {
            if (ok) {
                passed++;
            } else {
                failed++;
            }
        }
        out.println((ok ? "PASS " : "FAIL ") + text);
    }

    /** Prints an error and counts it as a failure. */
    private void report(Exception e) {
        synchronized (counterLock) {
            failed++;
        }
        if (e instanceof RafenDBRuntimeException || e instanceof RafenDBInternalException) {
            out.println(e.getMessage());
        } else {
            String message = e.getMessage();
            out.println(
                    "Unexpected error: "
                            + e.getClass().getSimpleName()
                            + (message == null ? "" : ": " + message));
        }
    }

    private static boolean skipped(String text) {
        return text.isEmpty() || text.startsWith("#") || text.startsWith("--");
    }

    /** Splits on whitespace; double quotes keep spaces inside one word and are removed. */
    private static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean has = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') {
                quoted = !quoted;
                has = true;
            } else if (Character.isWhitespace(ch) && !quoted) {
                if (has) {
                    words.add(current.toString());
                    current.setLength(0);
                    has = false;
                }
            } else {
                current.append(ch);
                has = true;
            }
        }
        if (has) {
            words.add(current.toString());
        }
        return words;
    }

    /** Returns the text after the first word, trimmed, exactly as typed. */
    private static String afterFirstWord(String line) {
        int i = 0;
        int n = line.length();
        while (i < n && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        boolean quoted = false;
        while (i < n && (quoted || !Character.isWhitespace(line.charAt(i)))) {
            if (line.charAt(i) == '"') {
                quoted = !quoted;
            }
            i++;
        }
        return line.substring(i).strip();
    }
}
