package org.rafendb.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.Component;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.error.StorageRuntimeException;

class DriverTest {
    @TempDir Path dir;

    private String output = "";

    /** A small driver exercising every hook and helper. */
    private static final class TestDriver extends Driver {
        final List<String> calls = new ArrayList<>();
        final List<String> fallbackLines = new ArrayList<>();
        boolean useFallback;
        boolean failSetup;
        boolean failClose;
        boolean registerHelp;
        private String greeting;
        private boolean loud;

        TestDriver() {
            super("Test Driver", "test> ");
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
                        String text = greeting + " " + args.getFirst();
                        out().println(loud ? text.toUpperCase() : text);
                    });
            command(
                    "words",
                    "words <w...>",
                    "print the words",
                    (args, rest) -> out().println(args));
            command(
                    "raw",
                    "raw <text>",
                    "print the raw text",
                    (args, rest) -> out().println("[" + rest + "]"));
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
                    "mocked",
                    "mocked <component>",
                    "print whether a component is mocked",
                    (args, rest) ->
                            out().println(mocked(Component.fromShortName(args.getFirst()))));
            command(
                    "parallel",
                    "parallel",
                    "check from two threads",
                    (args, rest) -> {
                        Thread a = new Thread(() -> check("from a", true));
                        Thread b = new Thread(() -> check("from b", 1, 2));
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
            fallbackLines.add(line);
        }

        @Override
        protected void help() {
            super.help();
            out().println("Driver flags:");
            out().println("  --greeting=<text>  greeting word (default hi)");
            out().println("  --loud             shout");
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

    @Test
    void helpPrintsUsageWithoutSetup() {
        TestDriver driver = new TestDriver();
        assertEquals(0, run(driver, "", "--help"));
        assertTrue(output.contains("Test Driver"), output);
        assertTrue(output.contains("Usage: Test Driver [options]"), output);
        assertTrue(output.contains("--mock=<list>"), output);
        assertTrue(output.contains("--greeting=<text>"), output);
        assertTrue(output.contains("Built-in commands:"), output);
        assertFalse(output.contains("greet someone"), "driver commands exist only after setup");
        assertTrue(
                output.indexOf("Built-in commands:") < output.indexOf("Driver flags:"),
                "the override prints after super.help()");
        assertEquals(List.of(), driver.calls);
    }

    @Test
    void badCommandLinesExitOneWithUsage() {
        assertEquals(1, run(new TestDriver(), "", "stray"));
        assertTrue(output.contains("Runtime error at General: unexpected argument: stray"), output);
        assertTrue(output.contains("Usage:"), output);
        assertEquals(1, run(new TestDriver(), "", "--loud", "--loud"));
        assertTrue(output.contains("--loud given more than once"), output);
        assertEquals(1, run(new TestDriver(), "", "--"));
        assertEquals(1, run(new TestDriver(), "", "--=x"));
        assertEquals(1, run(new TestDriver(), "", "x=y"));
        assertTrue(output.contains("unexpected argument: x=y"), output);
    }

    @Test
    void unreadFlagIsAnUnknownOption() {
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "", "--greting=hello"));
        assertTrue(output.contains("Runtime error at General: unknown option: --greting"), output);
        assertTrue(output.contains("Usage:"), output);
        assertEquals(List.of("setup", "close"), driver.calls);
    }

    @Test
    void optionsAndFlagsReachTheDriver() {
        assertEquals(0, run(new TestDriver(), "greet ana\n", "--greeting=hello", "--loud"));
        assertTrue(output.contains("HELLO ANA"), output);
        assertEquals(0, run(new TestDriver(), "greet ana\n"));
        assertTrue(output.contains("hi ana"), output);
    }

    @Test
    void wrongFlagFormIsASetupError() {
        TestDriver driver = new TestDriver();
        assertEquals(1, run(driver, "", "--greeting"));
        assertTrue(output.contains("--greeting needs a value: --greeting=<value>"), output);
        assertEquals(List.of("setup", "close"), driver.calls);
        assertEquals(1, run(new TestDriver(), "", "--loud=yes"));
        assertTrue(output.contains("--loud takes no value"), output);
    }

    @Test
    void mockFlagSelectsComponents() {
        assertEquals(0, run(new TestDriver(), "mocked sm\nmocked qp\n", "--mock=sm,FRM"));
        assertTrue(output.contains("Mocked: Storage Manager, Failure Recovery Manager"), output);
        assertTrue(output.contains("true"), output);
        assertTrue(output.contains("false"), output);
        assertEquals(0, run(new TestDriver(), "", "--mock=all"));
        assertTrue(
                output.contains(
                        "Mocked: Query Processor, Query Optimizer, Storage Manager,"
                                + " Concurrency Control Manager, Failure Recovery Manager"),
                output);
        assertEquals(0, run(new TestDriver(), ""));
        assertTrue(output.contains("Mocked: none"), output);
    }

    @Test
    void badMockNamesExitOne() {
        assertEquals(1, run(new TestDriver(), "", "--mock=gen"));
        assertTrue(output.contains("unknown component in --mock: gen"), output);
        assertEquals(1, run(new TestDriver(), "", "--mock=sm,bogus"));
        assertTrue(output.contains("unknown component in --mock: bogus"), output);
    }

    @Test
    void promptCommentsWordsAndRawText() {
        int code =
                run(
                        new TestDriver(),
                        "# comment\n-- sql comment\n\n  WORDS one \"two three\"\nraw  a \"b\"  'c  d'\n");
        assertEquals(0, code);
        assertTrue(output.contains("test> "), output);
        assertTrue(output.contains("[one, two three]"), output);
        assertTrue(output.contains("[a \"b\"  'c  d']"), output);
        assertFalse(output.contains("comment"), output);
    }

    @Test
    void helpCommandListsDriverAndBuiltInCommands() {
        run(new TestDriver(), "help\n");
        assertTrue(output.contains("Commands:"), output);
        assertTrue(output.contains("greet <name>"), output);
        assertTrue(output.contains("greet someone"), output);
        assertTrue(output.contains("Built-in commands:"), output);
        assertTrue(output.contains("run <file>"), output);
        assertTrue(output.contains("--mock=<list>"), output);
        assertTrue(output.contains("Driver flags:"), output);
    }

    @Test
    void unknownCommandWithoutFallbackIsAnError() {
        assertEquals(1, run(new TestDriver(), "tabels\ngreet bo\n"));
        assertTrue(
                output.contains(
                        "Runtime error at General: unknown command: tabels (hint: type help)"),
                output);
        assertTrue(output.contains("hi bo"), output);
    }

    @Test
    void fallbackGetsTheRawLine() {
        TestDriver driver = new TestDriver();
        driver.useFallback = true;
        assertEquals(0, run(driver, "SELECT *  FROM t WHERE name = 'a  b'\n"));
        assertEquals(List.of("SELECT *  FROM t WHERE name = 'a  b'"), driver.fallbackLines);
    }

    @Test
    void errorsArePrintedByTheirKindAndTheDriverGoesOn() {
        assertEquals(1, run(new TestDriver(), "sm-error\nfrm-error\nbug\ngreet bo\n"));
        assertTrue(
                output.contains("Runtime error at Storage Manager: table not found: nosuch"),
                output);
        assertTrue(
                output.contains("Internal error at Failure Recovery Manager: log is corrupt"),
                output);
        assertTrue(output.contains("Unexpected error: IllegalStateException: kaboom"), output);
        assertTrue(output.contains("hi bo"), output);
        assertFalse(output.contains("ERROR"), output);
    }

    @Test
    void driverExceptionFromACommand() {
        assertEquals(1, run(new TestDriver(), "greet\n"));
        assertTrue(output.contains("Runtime error at General: usage: greet <name>"), output);
    }

    @Test
    void checksShowExpectedAndActual() {
        assertEquals(0, run(new TestDriver(), "equals x x\nok true\n"));
        assertTrue(output.contains("PASS same: expected x, actual x"), output);
        assertTrue(output.contains("PASS condition"), output);
        assertEquals(1, run(new TestDriver(), "equals x y\nok false\n"));
        assertTrue(output.contains("FAIL same: expected x, actual y"), output);
        assertTrue(output.contains("FAIL condition"), output);
    }

    @Test
    void checksFromTwoThreadsAreBothCounted() throws IOException {
        Path f = file("threads.txt", "parallel");
        assertEquals(1, run(new TestDriver(), "", "--run=" + f));
        assertTrue(output.contains("PASS from a"), output);
        assertTrue(output.contains("FAIL from b: expected 1, actual 2"), output);
        assertTrue(output.contains("RESULT: 1 passed, 1 failed"), output);
    }

    @Test
    void runFileEchoesLinesAndPrintsItsResult() throws IOException {
        Path f =
                file(
                        "checks.txt",
                        "# comment",
                        "equals a a",
                        "greet \"Uji Coba\"",
                        "bug",
                        "ok true");
        run(new TestDriver(), "equals before before\nrun " + f + "\nquit\n");
        assertTrue(output.contains("> equals a a"), output);
        assertTrue(output.contains("hi Uji Coba"), output);
        assertFalse(output.contains("> # comment"), output);
        assertTrue(output.contains("RESULT: 2 passed, 1 failed"), output);
    }

    @Test
    void runFlagRunsTheFileAndExits() throws IOException {
        Path f = file("pass.txt", "equals a a", "greet bo");
        TestDriver driver = new TestDriver();
        assertEquals(0, run(driver, "greet never\n", "--run=" + f));
        assertTrue(output.contains("RESULT: 1 passed, 0 failed"), output);
        assertFalse(output.contains("never"), output);
        assertEquals(List.of("setup", "close"), driver.calls);
    }

    @Test
    void runInsideAFileIsAnError() throws IOException {
        Path inner = file("inner.txt", "greet x");
        Path outer = file("outer.txt", "run " + inner, "greet y");
        assertEquals(1, run(new TestDriver(), "", "--run=" + outer));
        assertTrue(output.contains("run cannot be used inside a file"), output);
        assertTrue(output.contains("hi y"), output);
        assertFalse(output.contains("hi x"), output);
    }

    @Test
    void missingFileIsAnError() {
        assertEquals(1, run(new TestDriver(), "", "--run=" + dir.resolve("nope.txt")));
        assertTrue(output.contains("Unexpected error: NoSuchFileException"), output);
        assertEquals(1, run(new TestDriver(), "run\n"));
        assertTrue(output.contains("usage: run <file>"), output);
    }

    @Test
    void quitStopsThePromptAndAFile() throws IOException {
        assertEquals(0, run(new TestDriver(), "greet a\nquit\ngreet never\n"));
        assertFalse(output.contains("never"), output);
        Path f = file("quit.txt", "greet a", "exit", "greet never");
        assertEquals(0, run(new TestDriver(), "", "--run=" + f));
        assertFalse(output.contains("hi never"), output);
    }

    @Test
    void closeIsAlwaysCalled() {
        TestDriver normal = new TestDriver();
        run(normal, "greet a\n");
        assertEquals(List.of("setup", "close"), normal.calls);

        TestDriver failedSetup = new TestDriver();
        failedSetup.failSetup = true;
        assertEquals(1, run(failedSetup, ""));
        assertTrue(output.contains("Unexpected error: IOException: disk gone"), output);
        assertEquals(List.of("setup", "close"), failedSetup.calls);

        TestDriver failedClose = new TestDriver();
        failedClose.failClose = true;
        assertEquals(1, run(failedClose, "greet a\n"));
        assertTrue(output.contains("Unexpected error: IOException: flush failed"), output);
    }

    @Test
    void builtInNamesCannotBeRegistered() {
        TestDriver driver = new TestDriver();
        driver.registerHelp = true;
        assertEquals(1, run(driver, ""));
        assertTrue(
                output.contains(
                        "Unexpected error: IllegalArgumentException: command already registered"),
                output);
    }

    @Test
    void driverExceptionIsAGeneralRuntimeError() {
        DriverException e =
                assertThrows(
                        DriverException.class,
                        () -> {
                            throw new DriverException("bad input");
                        });
        assertEquals("Runtime error at General: bad input", e.getMessage());
    }
}
