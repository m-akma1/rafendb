package org.rafendb.recovery.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggerTest {

    @TempDir Path tempDir;

    private Path logPath;

    @BeforeEach
    void setUp() {
        logPath = tempDir.resolve("wal.log");
    }

    @Test
    void testMockLoggerDefaults() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            assertEquals(0, logger.getLastCommittedLSN());
            assertEquals(0, logger.getHighestTransactionId());
            assertTrue(logger.getLogsSorted().isEmpty());
            assertTrue(logger.getLogsByTransaction(1).isEmpty());
            assertEquals(logPath.toString(), logger.getLogFilePath());
        }

        try (Logger defaultLogger = new Logger()) {
            assertEquals("", defaultLogger.getLogFilePath());
            assertEquals(0, defaultLogger.getLastCommittedLSN());
        }

        try (Logger strLogger = new Logger("test.log")) {
            assertEquals("test.log", strLogger.getLogFilePath());
        }
    }

    @Test
    void testMockLoggerLoggingAndRetrieval() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            logger.logTransactionEvent(1, LogType.BEGIN);
            int lsn = logger.logDataChange(1, LogType.INSERT, 1000L, "tbl", 1, null, "val");
            assertEquals(2, lsn);
            logger.logTransactionEvent(1, LogType.COMMIT);

            assertEquals(3, logger.getLastCommittedLSN());
            assertEquals(1, logger.getHighestTransactionId());

            List<WALRecord> logs = logger.getLogsSorted();
            assertEquals(3, logs.size());
            assertEquals(3, logger.getLogsByTransaction(1).size());
            assertTrue(logger.getLogsByTransaction(99).isEmpty());

            assertDoesNotThrow(() -> logger.flush());
            assertDoesNotThrow(() -> logger.flushAll());
            assertDoesNotThrow(() -> logger.flushLogUntil(10));
            assertDoesNotThrow(() -> logger.forceThrough(10));

            assertDoesNotThrow(() -> logger.setLastCommittedLSN(5));
            assertEquals(5, logger.getLastCommittedLSN());
            assertDoesNotThrow(() -> logger.setLastCommittedLSN());
            assertDoesNotThrow(() -> logger.displayLogs());
            assertDoesNotThrow(() -> logger.displayLogsByTransaction(1));
        }
    }
}
