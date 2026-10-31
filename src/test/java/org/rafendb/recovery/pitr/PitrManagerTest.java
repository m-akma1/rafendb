package org.rafendb.recovery.pitr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;

class PitrManagerTest {
    @Test
    void timestampTargetUsesCommitTimeAndExcludesLateCommit() throws InterruptedException {
        Logger logger = new Logger();
        PitrManager pitr = new PitrManager(logger);
        pitr.captureSnapshot(1, Map.of("accounts", Map.of(10, "opening")));

        logger.logTransactionEvent(10, LogType.BEGIN);
        logger.logTransactionEvent(2, LogType.BEGIN); // Began before target, commits afterward.
        writeChange(logger, 10, LogType.UPDATE, "opening", "early");
        logger.logTransactionEvent(10, LogType.COMMIT);
        long target = commit(logger, 10).getTimestamp();
        Thread.sleep(5);
        writeChange(logger, 2, LogType.UPDATE, "early", "late");
        logger.logTransactionEvent(2, LogType.COMMIT);

        assertTrue(pitr.recoverToTimestamp(target));
        assertEquals("early", pitr.getCurrentRows().get("accounts").get(10));
        assertEquals(1, pitr.getTransactionsReplayed());
        assertEquals(1, pitr.getChangesReplayed());
        int retainedRecords = pitr.getWalRecords().size();
        assertTrue(pitr.recoverToTimestamp(target));
        assertEquals("early", pitr.getCurrentRows().get("accounts").get(10));
        assertEquals(retainedRecords, pitr.getWalRecords().size());
        assertEquals(10, pitr.getRecoveredHighestTransactionId());
        assertTrue(pitr.getRecoveredHighestTimestamp() >= target);
    }

    @Test
    void transactionTargetUsesCommitLsnNotTransactionIdOrder() {
        Logger logger = new Logger();
        PitrManager pitr = new PitrManager(logger);
        pitr.captureSnapshot(1, Map.of("accounts", Map.of()));

        logger.logTransactionEvent(900, LogType.BEGIN);
        writeChange(logger, 900, LogType.INSERT, null, "first");
        logger.logTransactionEvent(900, LogType.COMMIT);
        logger.logTransactionEvent(2, LogType.BEGIN);
        writeChange(logger, 2, LogType.INSERT, null, "second");
        logger.logTransactionEvent(2, LogType.COMMIT);

        assertTrue(pitr.recoverToTransaction(2));
        assertEquals("first", pitr.getCurrentRows().get("accounts").get(900));
        assertEquals("second", pitr.getCurrentRows().get("accounts").get(2));
        assertEquals(2, pitr.getTransactionsReplayed());
    }

    @Test
    void ignoresAbortedAndIncompleteTransactionsAndRejectsInvalidTargets() {
        Logger logger = new Logger();
        PitrManager pitr = new PitrManager(logger);
        pitr.captureSnapshot(1, Map.of("accounts", Map.of()));

        logger.logTransactionEvent(1, LogType.BEGIN);
        writeChange(logger, 1, LogType.INSERT, null, "aborted");
        logger.logTransactionEvent(1, LogType.ABORT);
        logger.logTransactionEvent(2, LogType.BEGIN);
        writeChange(logger, 2, LogType.INSERT, null, "incomplete");

        assertFalse(pitr.recoverToTimestamp(System.currentTimeMillis() + 60_000));
        assertFalse(pitr.recoverToTransaction(2));
        assertTrue(pitr.recoverToTimestamp(1));
        assertTrue(pitr.getCurrentRows().get("accounts").isEmpty());
    }

    @Test
    void deleteAfterImageRemovesTheSameRecordIdentity() {
        Logger logger = new Logger();
        PitrManager pitr = new PitrManager(logger);
        pitr.captureSnapshot(1, Map.of("accounts", Map.of(3, "exists")));
        logger.logTransactionEvent(3, LogType.BEGIN);
        writeChange(logger, 3, LogType.DELETE, "exists", null);
        logger.logTransactionEvent(3, LogType.COMMIT);

        assertTrue(pitr.recoverToTimestamp(commit(logger, 3).getTimestamp()));
        assertFalse(pitr.getCurrentRows().get("accounts").containsKey(3));
    }

    private static void writeChange(
            Logger logger, int tx, LogType type, String before, String after) {
        logger.logDataChange(tx, type, System.currentTimeMillis(), "accounts", tx, before, after);
    }

    private static WALRecord commit(Logger logger, int tx) {
        return logger.getLogsSorted().stream()
                .filter(r -> r.getIdTransaction() == tx && r.getLogType() == LogType.COMMIT)
                .findFirst()
                .orElseThrow();
    }
}
