package org.rafendb.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.Component;
import org.rafendb.common.error.RafenDBRuntimeException;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.engine.Version;

/**
 * Tests {@link Driver} through a small subclass. Assertions use only exit codes, what the test
 * driver records in {@code calls}, and text the test itself supplies; never the base class's own
 * wording, so rewording a message or the help does not break them.
 */
class DriverTest {
    /** Printed by the test driver's {@code help()} override. */
    private static final String HELP_MARKER = "test-driver-help-marker";

    @TempDir Path dir;

    private String output = "";

    /** A small driver that records what reaches it. */
    private static final class TestDriver extends Driver {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        boolean useFallback;
        boolean failSetup;
        boolean failClose;
        boolean registerHelp;
        private String greeting;
        private boolean loud;

        TestDriver() {
            super(Component.QO);
        }

        @Override
        protected void setup() throws Exception {
            calls.add("setup");
            greeting = option("greeting", "hi");
            loud = flag("loud");
            if (failSetup) {
                throw new IOException("disk gone");
            }
            if (registerHelp) {
                command("help", "help", "clash", (args, rest) -> {});
            }
            command(
                    "greet",
                    "greet <name>",
                    "greet someone",
                    (args, rest) -> {
                        if (args.size() != 1) {
                            throw new DriverException("usage: greet <name>");
                        }
                        calls.add("greet " + greeting + " " + args.getFirst() + (loud ? "!" : ""));
                    });
            command(
                    "words",
                    "words <w...>",
                    "record the words",
                    (args, rest) -> calls.add("words " + args));
            command(
                    "raw",
                    "raw <text>",
                    "record the raw text",
                    (args, rest) -> calls.add("raw [" + rest + "]"));
            command(
                    "mocks",
                    "mocks",
                    "record the mocked components",
                    (args, rest) -> {
                        List<String> mocked = new ArrayList<>();
                        for (Component c : EnumSet.complementOf(EnumSet.of(Component.GENERAL))) {
                            if (mocked(c)) {
                                mocked.add(c.shortName());
                            }
                        }
                        calls.add("mocks " + mocked);
                    });
            command(
                    "sm-error",
                    "sm-error",
                    "throw a storage error",
                    (args, rest) -> {
                        throw new StorageRuntimeException("table not found: nosuch");
                    });
            command(
                    "frm-error",
                    "frm-error",
                    "throw a recovery error",
                    (args, rest) -> {
                        throw new RecoveryInternalException("log is corrupt");
                    });
            command(
                    "bug",
                    "bug",
                    "throw an unexpected error",
                    (args, rest) -> {
                        throw new IllegalStateException("kaboom");
                    });
            command(
                    "equals",
                    "equals <a> <b>",
                    "check a equals b",
                    (args, rest) -> check("same", args.get(0), args.get(1)));
            command(
                    "ok",
                    "ok <true|false>",
                    "check a condition",
                    (args, rest) -> check("condition", Boolean.parseBoolean(args.getFirst())));
            command(
                    "parallel",
                    "parallel",
                    "check from two threads",
                    (args, rest) -> {
                        Thread a = new Thread(() -> check("check-from-a", true));
                        Thread b = new Thread(() -> check("check-from-b", 1, 2));
                        a.start();
                        b.start();
                        a.join();
                        b.join();
                    });
        }

        @Override
        protected void fallback(String line) throws Exception {
            if (!useFallback) {
                super.fallback(line);
                return;
            }
            calls.add("fallback " + line);
        }

        @Override
        protected void help() {
            super.help();
            out().println(HELP_MARKER);
        }

        @Override
        protected void close() throws Exception {
            calls.add("close");
            if (failClose) {
                throw new IOException("flush failed");
            }
        }
    }

    private int run(Driver driver, String input, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code =
                driver.run(
                        args,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
        output = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    private Path file(String name, String... lines) throws IOException {
        return Files.write(dir.resolve(name), List.of(lines));
    }

    // ---- Flags ----

    @Test
    void helpFlagPrintsHelpWithoutSetup() {
        TestDriver driver = new TestDriver();
        assertEquals(0, run(driver, "", "--help"));
        assertTrue(output.contains(HELP_MARKER), output);
        assertEquals(List.of(), driver.calls);
    }

    @Test
    void versionFlagPrintsOnlyTheVersionWithoutSetup() {
        TestDriver driver = new TestDriver();
        assertEquals(0, run(driver, "", "--version", "--help"));
        assertTrue(output.contains(Version.current()), output);
        assertFalse(output.contains(HELP_MARKER), "--version wins over --help");
        assertEquals(List.of(), driver.calls);
    }

    @Test
    void malformedArgumentsStopBeforeSetup() {
        for (String[] args :
                List.of(
                        new String[] {"stray"},
                        new String[] {"--loud", "--loud"},
                        new String[] {"--"},
                        new String[] {"--=x"},
                        new String[] {"x=y"})) {
            TestDriver driver = new TestDriver();
            assertEquals(1, run(driver, "", args), String.join(" ", args));
            assertEquals(List.of(), driver.calls, String.join(" ", args));
            assertTrue(output.contains(HELP_MARKER), "help is shown after a flag error");
        }
    }

    @Test
    void unreadFlagStopsAfterSetup() {
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "greet ana\n", "--greting=hello"));
        assertTrue(output.contains("--greting"), output);
        assertEquals(List.of("setup", "close"), driver.calls);
    }

    @Test
    void optionsAndFlagsReachSetup() {
        TestDriver driver = new TestDriver();
        assertEquals(0, run(driver, "greet ana\n", "--greeting=hello", "--loud"));
        assertTrue(driver.calls.contains("greet hello ana!"), driver.calls.toString());

        TestDriver defaults = new TestDriver();
        assertEquals(0, run(defaults, "greet ana\n"));
        assertTrue(defaults.calls.contains("greet hi ana"), defaults.calls.toString());
    }

    @Test
    void wrongFlagFormFailsSetup() {
        TestDriver bareValue = new TestDriver();
        assertEquals(1, run(bareValue, "greet ana\n", "--greeting"));
        assertEquals(List.of("setup", "close"), bareValue.calls);

        TestDriver valuedSwitch = new TestDriver();
        assertEquals(1, run(valuedSwitch, "greet ana\n", "--loud=yes"));
        assertEquals(List.of("setup", "close"), valuedSwitch.calls);
    }

    // ---- --mock ----

    @Test
    void mockFlagSelectsComponents() {
        TestDriver some = new TestDriver();
        assertEquals(0, run(some, "mocks\n", "--mock=sm,FRM"));
        assertTrue(some.calls.contains("mocks [SM, FRM]"), some.calls.toString());

        TestDriver all = new TestDriver();
        assertEquals(0, run(all, "mocks\n", "--mock=all"));
        assertTrue(all.calls.contains("mocks [QP, SM, CCM, FRM]"), all.calls.toString());

        TestDriver none = new TestDriver();
        assertEquals(0, run(none, "mocks\n"));
        assertTrue(none.calls.contains("mocks []"), none.calls.toString());
    }

    @Test
    void badMockListsStopBeforeSetup() {
        for (String list : List.of("--mock=gen", "--mock=sm,bogus", "--mock=sm,QO")) {
            TestDriver driver = new TestDriver();
            assertEquals(1, run(driver, "", list), list);
            assertEquals(List.of(), driver.calls, list);
        }
    }

    @Test
    void generalCannotHaveADriver() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Driver(Component.GENERAL) {
                            @Override
                            protected void setup() {}
                        });
    }

    // ---- Lines and commands ----

    @Test
    void commandsGetWordsAndRawTextAndCommentsAreSkipped() {
        TestDriver driver = new TestDriver();
        String input =
                "# comment\n-- sql comment\n\n  WORDS one \"two three\"\nraw  a \"b\"  'c  d'\n";
        assertEquals(0, run(driver, input));
        assertEquals(
                List.of("setup", "words [one, two three]", "raw [a \"b\"  'c  d']", "close"),
                driver.calls);
    }

    @Test
    void helpCommandListsTheDriversCommands() {
        assertEquals(0, run(new TestDriver(), "help\n"));
        assertTrue(output.contains("greet <name>"), output);
        assertTrue(output.contains("greet someone"), output);
        assertTrue(output.contains(HELP_MARKER), output);
    }

    @Test
    void unknownCommandWithoutFallbackFailsAndTheDriverGoesOn() {
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "tabels\ngreet bo\n"));
        assertTrue(output.contains("tabels"), output);
        assertTrue(driver.calls.contains("greet hi bo"), driver.calls.toString());
    }

    @Test
    void fallbackGetsTheRawLine() {
        TestDriver driver = new TestDriver();
        driver.useFallback = true;
        assertEquals(0, run(driver, "SELECT *  FROM t WHERE name = 'a  b'\n"));
        assertTrue(
                driver.calls.contains("fallback SELECT *  FROM t WHERE name = 'a  b'"),
                driver.calls.toString());
    }

    @Test
    void builtInNamesCannotBeRegistered() {
        TestDriver driver = new TestDriver();
        driver.registerHelp = true;
        assertEquals(1, run(driver, "greet ana\n"));
        assertEquals(List.of("setup", "close"), driver.calls);
    }

    // ---- Errors ----

    @Test
    void errorsArePrintedCountedAndTheDriverGoesOn() {
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "sm-error\nfrm-error\nbug\ngreet bo\n"));
        assertTrue(output.contains("table not found: nosuch"), output);
        assertTrue(output.contains("log is corrupt"), output);
        assertTrue(output.contains("kaboom"), output);
        assertTrue(output.contains("IllegalStateException"), "unexpected errors show their type");
        assertFalse(output.contains("StorageRuntimeException"), "RafenDB errors print as is");
        assertTrue(driver.calls.contains("greet hi bo"), driver.calls.toString());
    }

    @Test
    void driverExceptionFromACommandIsReported() {
        assertEquals(1, run(new TestDriver(), "greet\n"));
        assertTrue(output.contains("usage: greet <name>"), output);
    }

    @Test
    void driverExceptionIsARafenDBRuntimeError() {
        DriverException e = new DriverException("bad input");
        assertInstanceOf(RafenDBRuntimeException.class, e);
        assertTrue(e.getMessage().contains("bad input"), e.getMessage());
    }

    // ---- Checks ----

    @Test
    void checksDecideTheExitCodeAndShowBothValues() {
        assertEquals(0, run(new TestDriver(), "equals same-x same-x\nok true\n"));
        assertEquals(1, run(new TestDriver(), "equals left-value right-value\n"));
        assertTrue(output.contains("left-value"), output);
        assertTrue(output.contains("right-value"), output);
        assertEquals(1, run(new TestDriver(), "ok false\n"));
    }

    @Test
    void checksFromTwoThreadsAreBothRecorded() {
        assertEquals(1, run(new TestDriver(), "parallel\n"));
        assertTrue(output.contains("check-from-a"), output);
        assertTrue(output.contains("check-from-b"), output);
    }

    // ---- Files ----

    @Test
    void runCommandRunsAFileAndTheDriverGoesOn() throws IOException {
        Path f = file("cmds.txt", "# comment", "greet a", "bug", "greet b");
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "run " + f + "\ngreet after\n"));
        assertEquals(
                List.of("setup", "greet hi a", "greet hi b", "greet hi after", "close"),
                driver.calls);
    }

    @Test
    void runFlagRunsTheFileInsteadOfTheInput() throws IOException {
        Path f = file("pass.txt", "equals a a", "greet bo");
        TestDriver driver = new TestDriver();
        assertEquals(0, run(driver, "greet never\n", "--run=" + f));
        assertEquals(List.of("setup", "greet hi bo", "close"), driver.calls);
    }

    @Test
    void runInsideAFileFails() throws IOException {
        Path inner = file("inner.txt", "greet x");
        Path outer = file("outer.txt", "run " + inner, "greet y");
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "", "--run=" + outer));
        assertEquals(List.of("setup", "greet hi y", "close"), driver.calls);
    }

    @Test
    void missingFileOrMissingArgumentFails() {
        assertEquals(1, run(new TestDriver(), "", "--run=" + dir.resolve("nope.txt")));
        assertTrue(output.contains("nope.txt"), output);
        assertEquals(1, run(new TestDriver(), "run\n"));
    }

    @Test
    void quitStopsThePromptAndAFile() throws IOException {
        TestDriver prompt = new TestDriver();
        assertEquals(0, run(prompt, "greet a\nquit\ngreet never\n"));
        assertEquals(List.of("setup", "greet hi a", "close"), prompt.calls);

        Path f = file("quit.txt", "greet a", "exit", "greet never");
        TestDriver fromFile = new TestDriver();
        assertEquals(0, run(fromFile, "", "--run=" + f));
        assertEquals(List.of("setup", "greet hi a", "close"), fromFile.calls);
    }

    // ---- Lifecycle ----

    @Test
    void closeIsAlwaysCalledAndItsFailureCounts() {
        TestDriver failedSetup = new TestDriver();
        failedSetup.failSetup = true;
        assertEquals(1, run(failedSetup, "greet a\n"));
        assertTrue(output.contains("disk gone"), output);
        assertEquals(List.of("setup", "close"), failedSetup.calls);

        TestDriver failedClose = new TestDriver();
        failedClose.failClose = true;
        assertEquals(1, run(failedClose, "greet a\n"));
        assertTrue(output.contains("flush failed"), output);
        assertEquals(List.of("setup", "greet hi a", "close"), failedClose.calls);
    }
}
