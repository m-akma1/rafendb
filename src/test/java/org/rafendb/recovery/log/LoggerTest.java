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
                    public synchronized void flushAll() throws LoggerErrorException {
                        if (shouldFail[0]) {
                            throw new LoggerErrorException("Simulated disk sync failure");
                        }
                        super.flushAll();
                    }
                }) {
            failingFlushLogger.logTransactionEvent(1, LogType.BEGIN);
            long sizeAfterBegin = Files.size(logPath);
            assertThrows(
                    LoggerErrorException.class,
                    () -> failingFlushLogger.logTransactionEvent(1, LogType.COMMIT));
            assertEquals(0L, failingFlushLogger.getLastCommittedLSN());
            assertEquals(1, failingFlushLogger.getLogsSorted().size());
            assertEquals(1L, failingFlushLogger.getCurrentLSN());
            assertEquals(sizeAfterBegin, Files.size(logPath));

            shouldFail[0] = false;
            failingFlushLogger.logTransactionEvent(1, LogType.ABORT);
            failingFlushLogger.flushAll();
        }

        try (Logger reloaded = new Logger(logPath)) {
            List<WALRecord> recs = reloaded.getLogsSorted();
            assertEquals(2, recs.size());
            assertEquals(1L, recs.get(0).getLSN());
            assertEquals(LogType.BEGIN, recs.get(0).getLogType());
            assertEquals(2L, recs.get(1).getLSN());
            assertEquals(LogType.ABORT, recs.get(1).getLogType());
        }
    }

    @Test
    void testMiddleLogCorruptionThrowsClearErrorWithoutTruncating() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            logger.logTransactionEvent(1, LogType.BEGIN);
            logger.logDataChange(1, LogType.INSERT, 1000L, "tbl", 3L, new Rid(0L, 1), null, "v1");
            logger.logTransactionEvent(1, LogType.COMMIT);
            logger.flushAll();
        }

        byte[] bytes = Files.readAllBytes(logPath);
        int firstLen = java.nio.ByteBuffer.wrap(bytes, 0, 4).getInt();
        bytes[4 + firstLen + 4 + 8] ^= 0x55;
        Files.write(logPath, bytes);

        LoggerErrorException ex =
                assertThrows(LoggerErrorException.class, () -> new Logger(logPath));
        assertTrue(ex.getMessage().toLowerCase().contains("middle of log"));
        assertEquals(bytes.length, Files.size(logPath));
    }

    @Test
    void testCorruptLengthInMiddleOfLogThrowsWithoutTruncating() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            logger.logTransactionEvent(1, LogType.BEGIN);
            logger.logDataChange(1, LogType.INSERT, 1000L, "tbl", 3L, new Rid(0L, 1), null, "v1");
            logger.logTransactionEvent(1, LogType.COMMIT);
            logger.flushAll();
        }

        byte[] bytes = Files.readAllBytes(logPath);
        int firstLen = java.nio.ByteBuffer.wrap(bytes, 0, 4).getInt();
        int secondLenOffset = 4 + firstLen;
        bytes[secondLenOffset] = (byte) 0xFF;
        bytes[secondLenOffset + 1] = (byte) 0xFF;
        bytes[secondLenOffset + 2] = (byte) 0xFF;
        bytes[secondLenOffset + 3] = (byte) 0xFF;
        Files.write(logPath, bytes);

        LoggerErrorException ex =
                assertThrows(LoggerErrorException.class, () -> new Logger(logPath));
        assertTrue(ex.getMessage().toLowerCase().contains("middle of log"));
        assertEquals(bytes.length, Files.size(logPath));
    }

    @Test
    void testCorruptLengthAtVeryEndOfFileTruncatesAsTornTail() throws Exception {
        long validFileSize;
        try (Logger logger1 = new Logger(logPath)) {
            logger1.logTransactionEvent(1, LogType.BEGIN);
            logger1.flushAll();
        }
        validFileSize = Files.size(logPath);

        Files.write(logPath, new byte[] {0, 0, 0, 0}, StandardOpenOption.APPEND);
        try (Logger logger2 = new Logger(logPath)) {
            assertEquals(1, logger2.getLogsSorted().size());
            assertEquals(validFileSize, Files.size(logPath));
        }
    }

    @Test
    void testRejectsRecordExceedingMaxBytesWithoutCorruptingWal() throws Exception {
        String huge = "x".repeat(9 * 1024 * 1024);
        try (Logger logger = new Logger(logPath)) {
            logger.logTransactionEvent(1, LogType.BEGIN);
            assertThrows(
                    LoggerErrorException.class,
                    () ->
                            logger.logDataChange(
                                    1,
                                    LogType.UPDATE,
                                    1000L,
                                    "tbl",
                                    1L,
                                    new Rid(0L, 1),
                                    huge,
                                    huge));
            assertEquals(1L, logger.getCurrentLSN());
            logger.logTransactionEvent(1, LogType.COMMIT);
        }

        try (Logger reloaded = new Logger(logPath)) {
            assertEquals(2, reloaded.getLogsSorted().size());
        }
    }

    @Test
    void testClosedLoggerRejectsWriteAndForceThrough() throws Exception {
        Logger logger = new Logger(logPath);
        logger.logTransactionEvent(1, LogType.BEGIN);
        logger.close();
        assertDoesNotThrow(logger::close);

        assertThrows(LoggerErrorException.class, () -> logger.forceThrough(1L));
        assertThrows(LoggerErrorException.class, logger::flushAll);
        assertThrows(
                LoggerErrorException.class, () -> logger.logTransactionEvent(2, LogType.BEGIN));
        assertThrows(
                LoggerErrorException.class,
                () ->
                        logger.logDataChange(
                                1, LogType.INSERT, 1000L, "tbl", 1L, new Rid(0L, 1), null, "v"));
    }

    @Test
    void testWalRecordV2DetectsCorruptedLegacyIdRecordBytes() {
        WALRecord original =
                new WALRecord(
                        5L, 2, LogType.UPDATE, 999L, "users", 7L, new Rid(3L, 42), "old", "new");
        byte[] bytes = original.serialize();
        int tableLen = "users".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        // magic(4) + lsn(8) + txId(4) + writeUTF("UPDATE": 2+6=8) + ts(8) + tableLen(4) +
        // tableBytes(5) + gen(8)
        int idRecordOffset = 4 + 8 + 4 + (2 + 6) + 8 + 4 + tableLen + 8;
        bytes[idRecordOffset] ^= 0x01;

        assertThrows(IllegalArgumentException.class, () -> WALRecord.deserialize(bytes));
    }

    @Test
    void testAppendRecordOutOfOrderMaintainsSortedLogs() throws Exception {
        try (Logger logger = new Logger(logPath)) {
            logger.appendRecord(new WALRecord(5L, 1, LogType.BEGIN, 100L, null, -1, null, null));
            logger.appendRecord(new WALRecord(2L, 1, LogType.COMMIT, 200L, null, -1, null, null));
            List<WALRecord> sorted = logger.getLogsSorted();
            assertEquals(2, sorted.size());
            assertEquals(2L, sorted.get(0).getLSN());
            assertEquals(5L, sorted.get(1).getLSN());
        }
    }
}
