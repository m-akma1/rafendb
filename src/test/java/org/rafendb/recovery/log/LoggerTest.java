package org.rafendb.recovery.log;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rafendb.common.tuple.Rid;

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
            assertEquals(0L, logger.getLastCommittedLSN());
            assertEquals(0, logger.getHighestTransactionId());
            assertTrue(logger.getLogsSorted().isEmpty());
            assertTrue(logger.getLogsByTransaction(1).isEmpty());
            assertEquals(logPath.toString(), logger.getLogFilePath());
        }

        try (Logger defaultLogger = new Logger()) {
            assertEquals("", defaultLogger.getLogFilePath());
            assertEquals(0L, defaultLogger.getLastCommittedLSN());
        }

        try (Logger strLogger = new Logger("test.log")) {
            assertEquals("test.log", strLogger.getLogFilePath());
        }
    }

    @Test
    void testMockLoggerLoggingAndRetrieval() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            logger.logTransactionEvent(1, LogType.BEGIN);
            long lsn = logger.logDataChange(1, LogType.INSERT, 1000L, "tbl", 1, null, "val");
            assertEquals(2L, lsn);
            logger.logTransactionEvent(1, LogType.COMMIT);

            assertEquals(3L, logger.getLastCommittedLSN());
            assertEquals(3L, logger.getCurrentLSN());
            assertEquals(1, logger.getHighestTransactionId());

            List<WALRecord> logs = logger.getLogsSorted();
            assertEquals(3, logs.size());
            assertEquals(3, logger.getLogsByTransaction(1).size());
            assertTrue(logger.getLogsByTransaction(99).isEmpty());

            assertDoesNotThrow(() -> logger.flush());
            assertDoesNotThrow(() -> logger.flushAll());
            assertDoesNotThrow(() -> logger.flushLogUntil(10L));
            assertDoesNotThrow(() -> logger.forceThrough(10L));

            assertDoesNotThrow(() -> logger.setLastCommittedLSN(5L));
            assertEquals(5L, logger.getLastCommittedLSN());
            assertDoesNotThrow(() -> logger.setLastCommittedLSN());
            assertDoesNotThrow(() -> logger.displayLogs());
            assertDoesNotThrow(() -> logger.displayLogsByTransaction(1));
        }
    }

    @Test
    void testPersistenceAndReloadFromFile() throws Exception {
        try (Logger logger1 = new Logger(logPath)) {
            logger1.logTransactionEvent(1, LogType.BEGIN);
            logger1.logDataChange(1, LogType.INSERT, 1000L, "tbl", 10, null, "v1");
            logger1.logTransactionEvent(1, LogType.COMMIT);
            logger1.flushAll();
        }

        // Buka logger baru dari file yang sama di disk
        try (Logger logger2 = new Logger(logPath)) {
            assertEquals(3, logger2.getLogsSorted().size());
            assertEquals(3L, logger2.getCurrentLSN());
            assertEquals(3L, logger2.getLastCommittedLSN());
            assertEquals(1, logger2.getHighestTransactionId());

            // Tambahkan record baru setelah restart
            logger2.logTransactionEvent(2, LogType.BEGIN);
            assertEquals(4L, logger2.getCurrentLSN());
            assertEquals(4, logger2.getLogsSorted().size());
        }
    }

    @Test
    void testWalRecordV2ChecksumTableGenerationAndLargeRid() {
        Rid largeRid = new Rid(42L, 1250);
        WALRecord original =
                new WALRecord(7L, 3, LogType.UPDATE, 123456L, "orders", 9L, largeRid, "old", "new");
        byte[] serialized = original.serialize();
        WALRecord decoded = WALRecord.deserialize(serialized);

        assertEquals(7L, decoded.getLSN());
        assertEquals(9L, decoded.getTableGeneration());
        assertEquals(largeRid, decoded.getRid());
        assertEquals("old", decoded.getBeforeValue());
        assertEquals("new", decoded.getAfterValue());
        assertEquals(original.getChecksum(), decoded.getChecksum());
    }

    @Test
    void testWalRecordChecksumDetectsCorruption() {
        WALRecord original =
                new WALRecord(1L, 1, LogType.INSERT, 100L, "users", 2L, new Rid(1L, 5), null, "v");
        byte[] corrupted = original.serialize();
        corrupted[corrupted.length - 10] ^= 0x55;

        assertThrows(IllegalArgumentException.class, () -> WALRecord.deserialize(corrupted));
    }

    @Test
    void testTornTailIsTruncatedOnReload() throws Exception {
        long validFileSize;
        try (Logger logger1 = new Logger(logPath)) {
            logger1.logTransactionEvent(1, LogType.BEGIN);
            logger1.logDataChange(1, LogType.INSERT, 1000L, "tbl", 5L, new Rid(0L, 1), null, "v1");
            logger1.flushAll();
        }
        validFileSize = Files.size(logPath);

        // Simulasikan torn-tail akibat crash saat menulis record ke-3
        Files.write(logPath, new byte[] {0, 0, 0, 50, 1, 2, 3, 4}, StandardOpenOption.APPEND);
        assertTrue(Files.size(logPath) > validFileSize);

        try (Logger logger2 = new Logger(logPath)) {
            assertEquals(2, logger2.getLogsSorted().size());
            assertEquals(validFileSize, Files.size(logPath));
            assertEquals(5L, logger2.getLogsSorted().get(1).getTableGeneration());
        }
    }

    @Test
    void testEmptyLogDisplayIsSafeAndReadOnly() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            assertDoesNotThrow(logger::displayLogs);
            assertDoesNotThrow(() -> logger.displayLogsByTransaction(42));
            assertTrue(logger.getLogsSorted().isEmpty());
        }
    }

    @Test
    void testFailedCommitFlushDoesNotAdvanceCommittedState() throws Exception {
        boolean[] shouldFail = {true};
        try (Logger failingFlushLogger =
                new Logger(logPath) {
                    @Override
                    public synchronized void flushAll() {
                        if (shouldFail[0]) {
                            throw new LoggerErrorException("Simulated disk sync failure");
                        }
                        super.flushAll();
                    }
                }) {
            failingFlushLogger.logTransactionEvent(1, LogType.BEGIN);
            assertThrows(
                    LoggerErrorException.class,
                    () -> failingFlushLogger.logTransactionEvent(1, LogType.COMMIT));
            assertEquals(0L, failingFlushLogger.getLastCommittedLSN());
            assertEquals(1, failingFlushLogger.getLogsSorted().size());
            assertEquals(1L, failingFlushLogger.getCurrentLSN());
            shouldFail[0] = false;
        }
    }
}
