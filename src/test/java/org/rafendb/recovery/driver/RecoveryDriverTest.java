package org.rafendb.recovery.driver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.engine.Version;

/**
 * Pengujian unit dan skenario untuk {@link RecoveryDriver} yang mewarisi {@link
 * org.rafendb.driver.Driver}.
 */
class RecoveryDriverTest {

    private String output = "";

    private int runDriver(RecoveryDriver driver, String input, String... args) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code =
                driver.run(
                        args,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(bytes, true, StandardCharsets.UTF_8));
        output = bytes.toString(StandardCharsets.UTF_8);
        return code;
    }

    @Test
    void testRecoveryDriverRunAllTests() throws Exception {
        RecoveryDriver driver = new RecoveryDriver();
        try {
            boolean success = driver.runAllTests();
            assertTrue(success, "All integration tests in RecoveryDriver should pass");
        } finally {
            driver.close();
        }
    }

    @Test
    void testHelpAndVersionFlags() {
        RecoveryDriver helpDriver = new RecoveryDriver();
        assertEquals(0, runDriver(helpDriver, "", "--help"));
        assertTrue(output.contains("--dir="), output);
        assertTrue(output.contains("--wal="), output);
        assertTrue(output.contains("--frames="), output);

        RecoveryDriver versionDriver = new RecoveryDriver();
        assertEquals(0, runDriver(versionDriver, "", "--version"));
        assertTrue(output.contains(Version.current()), output);
    }

    @Test
    void testCommandRunsAllScenariosViaDriver() {
        RecoveryDriver driver = new RecoveryDriver();
        int code = runDriver(driver, "test\nquit\n", "--mock=sm,ccm");
        assertEquals(0, code, output);
        assertTrue(output.contains("PASS frm integration test suite"), output);
        assertTrue(output.contains("ALL TESTS PASSED!"), output);
    }

    @Test
    void testScriptWithMockSmAndCcm(@TempDir Path tempDir) throws Exception {
        Path script =
                Files.write(
                        tempDir.resolve("mock-scenario.txt"),
                        List.of(
                                "begin 1",
                                "insert 1 accounts 1 Alice",
                                "commit 1",
                                "checkpoint",
                                "begin 2",
                                "update-wal 2 accounts 1 Alice-V2",
                                "commit 2",
                                "begin 3",
                                "insert 3 accounts 2 Dirty",
                                "crash",
                                "recover",
                                "logs",
                                "tx 1",
                                "status 1",
                                "get accounts 1",
                                "assert val accounts 1 Alice-V2",
                                "assert val accounts 2 null",
                                "assert status 1 COMMITTED",
                                "assert logs 6"));

        RecoveryDriver driver = new RecoveryDriver();
        int code =
                runDriver(
                        driver,
                        "",
                        "--mock=sm,ccm",
                        "--dir=" + tempDir.resolve("work"),
                        "--run=" + script);
        assertEquals(0, code, output);
        assertTrue(output.contains("0 failed"), output);
        assertTrue(output.contains("WAL RECORD VIEWER"), output);
        assertTrue(output.contains("Filtered for Transaction: 1"), output);
    }

    @Test
    void testScriptWithRealSmAndCcm(@TempDir Path tempDir) throws Exception {
        Path script =
                Files.write(
                        tempDir.resolve("real-scenario.txt"),
                        List.of(
                                "begin 1",
                                "insert 1 accounts 1 Alice",
                                "commit 1",
                                "checkpoint",
                                "begin 2",
                                "update-wal 2 accounts 1 Alice-V2",
                                "commit 2",
                                "begin 3",
                                "insert 3 accounts 2 Dirty",
                                "crash",
                                "recover",
                                "assert val accounts 1 Alice-V2",
                                "assert val accounts 2 null",
                                "assert status 1 COMMITTED",
                                "pitr-tx 1",
                                "assert val accounts 1 Alice"));

        RecoveryDriver driver = new RecoveryDriver();
        int code =
                runDriver(
                        driver,
                        "",
                        "--dir=" + tempDir.resolve("real-work"),
                        "--frames=8",
                        "--run=" + script);
        assertEquals(0, code, output);
        assertTrue(output.contains("0 failed"), output);
        assertTrue(output.contains("Mocked components: none"), output);
    }

    @Test
    void testRejectsMockOwnComponentAndUnknownFlag() {
        assertEquals(1, runDriver(new RecoveryDriver(), "", "--mock=frm"));
        assertTrue(output.contains("frm"), output);

        assertEquals(1, runDriver(new RecoveryDriver(), "", "--unknown-flag"));
        assertTrue(output.contains("--unknown-flag"), output);
    }
}
