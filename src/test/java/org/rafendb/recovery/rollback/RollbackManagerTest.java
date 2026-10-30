package org.rafendb.recovery.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.WALRecord;

class RollbackManagerTest {

    /** Log di memori. Mencatat setiap panggilan agar test bisa mengecek urutan tulis log dan data. */
    static final class FakeLog implements RollbackLog {
        final List<WALRecord> records = new ArrayList<>();
        final List<String> events;
        long now = 0;

        FakeLog(List<String> events) {
            this.events = events;
        }

        /** Mencatat perubahan seperti yang dilakukan CCM sebelum menyuruh SM menerapkannya. */
        int change(int tx, LogType type, String table, int id, String before, String after) {
            return logDataChange(tx, type, ++now, table, id, before, after);
        }

        @Override
        public synchronized List<WALRecord> getLogsSorted() {
            return List.copyOf(records);
        }

        @Override
        public synchronized List<WALRecord> getLogsByTransaction(int tx) {
            return records.stream().filter(r -> r.getIdTransaction() == tx).toList();
        }

        @Override
        public synchronized void logTransactionEvent(int tx, LogType type) {
            records.add(new WALRecord(records.size() + 1, tx, type, ++now, null, -1, null, null));
            events.add("log " + type + " T" + tx);
        }

        @Override
        public synchronized int logDataChange(
                int tx, LogType type, long ts, String table, int id, String before, String after) {
            int lsn = records.size() + 1;
            records.add(new WALRecord(lsn, tx, type, ts, table, id, before, after));
            events.add("log " + type + " " + table + "#" + id);
            return lsn;
        }

        @Override
        public void flushAll() {
            events.add("flush");
        }

        @Override
        public synchronized int getHighestTransactionId() {
            return records.stream().mapToInt(WALRecord::getIdTransaction).max().orElse(0);
        }
    }

    /** Data tabel di memori yang bisa disuruh gagal pada undo berikutnya. */
    static final class FakeStorage implements UndoStorage {
        final Map<String, String> rows = new HashMap<>();
        final List<String> events;
        int failNext = 0;

        FakeStorage(List<String> events) {
            this.events = events;
        }

        void put(String table, int id, String image) {
            if (image == null) {
                rows.remove(table + "#" + id);
            } else {
                rows.put(table + "#" + id, image);
            }
        }

        String get(String table, int id) {
            return rows.get(table + "#" + id);
        }

        @Override
        public void applyUndo(String table, int id, String image, int lsn) {
            if (failNext > 0) {
                failNext--;
                throw new IllegalStateException("disk error");
            }
            put(table, id, image);
            events.add("apply " + table + "#" + id);
        }
    }

    List<String> events;
    FakeLog log;
    FakeStorage db;
    RollbackManager frm;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        log = new FakeLog(events);
        db = new FakeStorage(events);
        frm = new RollbackManager(log, db, () -> ++log.now);
    }

    /** Menjalankan perubahan seperti CCM + SM: tulis log dulu, baru terapkan ke data. */
    void write(int tx, LogType type, String table, int id, String after) {
        String before = db.get(table, id);
        log.change(tx, type, table, id, before, after);
        db.put(table, id, after);
    }

    @Test
    void rollbackUndoesInsertUpdateDeleteLatestFirst() {
        db.put("account", 1, "{bal:10}");
        db.put("account", 2, "{bal:20}");
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.UPDATE, "account", 1, "{bal:5}");
        write(1, LogType.UPDATE, "account", 1, "{bal:0}");
        write(1, LogType.DELETE, "account", 2, null);
        write(1, LogType.INSERT, "account", 3, "{bal:99}");

        assertTrue(frm.rollbackTransaction(1));

        assertEquals("{bal:10}", db.get("account", 1));
        assertEquals("{bal:20}", db.get("account", 2));
        assertEquals(null, db.get("account", 3));
        List<WALRecord> t1 = log.getLogsByTransaction(1);
        assertEquals(LogType.ABORT, t1.getLast().getLogType());
        assertEquals(LogType.DELETE, t1.get(5).getLogType()); // kompensasi dari INSERT
    }

    @Test
    void eachCompensationIsLoggedBeforeItsDataIsApplied() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 7, "{x}");
        events.clear();

        frm.rollbackTransaction(1);

        assertEquals(List.of("log DELETE item#7", "apply item#7", "log ABORT T1", "flush"), events);
    }

    @Test
    void rollbackLeavesOtherCommittedTransactionsAlone() {
        log.logTransactionEvent(1, LogType.BEGIN);
        log.logTransactionEvent(2, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(2, LogType.COMMIT);

        frm.rollbackTransaction(1);

        assertEquals(null, db.get("item", 1));
        assertEquals("{b}", db.get("item", 2));
    }

    @Test
    void committedOrUnknownTransactionIsNotRolledBack() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        log.logTransactionEvent(1, LogType.COMMIT);

        assertFalse(frm.rollbackTransaction(1));
        assertFalse(frm.rollbackTransaction(42));
        assertEquals("{a}", db.get("item", 1));
    }

    @Test
    void secondRollbackChangesNothing() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        frm.rollbackTransaction(1);
        int size = log.records.size();

        assertTrue(frm.rollbackTransaction(1));
        assertEquals(size, log.records.size());
    }

    @Test
    void failedRollbackResumesWithoutUndoingTwice() {
        db.put("account", 1, "{bal:10}");
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.UPDATE, "account", 1, "{bal:5}");
        write(1, LogType.INSERT, "account", 2, "{bal:1}");
        db.failNext = 1;

        assertThrows(IllegalStateException.class, () -> frm.rollbackTransaction(1));
        assertTrue(frm.rollbackTransaction(1));

        assertEquals("{bal:10}", db.get("account", 1));
        assertEquals(null, db.get("account", 2));
        // BEGIN, 2 perubahan, 2 kompensasi (tidak ada yang ditulis dua kali), ABORT
        assertEquals(6, log.getLogsByTransaction(1).size());
    }

    @Test
    void emptyLogDoesNotCrash() {
        assertFalse(frm.rollbackTransaction(1));
        assertEquals(0, frm.recoverToPointInTime(1000));
    }

    @Test
    void recoverToPointInTimeKeepsOnlyWhatWasCommittedThen() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        log.logTransactionEvent(1, LogType.COMMIT);
        long target = log.now;
        log.logTransactionEvent(2, LogType.BEGIN);
        write(2, LogType.UPDATE, "item", 1, "{a2}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(2, LogType.COMMIT);

        assertEquals(2, frm.recoverToPointInTime(target));

        assertEquals("{a}", db.get("item", 1));
        assertEquals(null, db.get("item", 2));
        assertEquals(LogType.COMMIT, log.records.getLast().getLogType());
        assertEquals(3, log.records.getLast().getIdTransaction()); // transaksi sistem
    }

    @Test
    void recoverToTransactionDropsTransactionsCommittedLater() {
        log.logTransactionEvent(1, LogType.BEGIN);
        log.logTransactionEvent(2, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}"); // T1 mulai sebelum T2 commit ...
        write(2, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(2, LogType.COMMIT);
        log.logTransactionEvent(1, LogType.COMMIT); // ... tetapi commit sesudahnya

        frm.recoverToTransaction(2);

        assertEquals(null, db.get("item", 1));
        assertEquals("{b}", db.get("item", 2));
    }

    @Test
    void restoreIsRejectedWhileATransactionRuns() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");

        assertThrows(IllegalStateException.class, () -> frm.recoverToPointInTime(0));
        assertThrows(IllegalArgumentException.class, () -> frm.recoverToTransaction(1));
        assertEquals("{a}", db.get("item", 1));
    }

    @Test
    void failedRestoreIsRolledBack() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        write(1, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(1, LogType.COMMIT);
        frm = new RollbackManager(log, failOnSecond(), () -> ++log.now);
        assertThrows(IllegalStateException.class, () -> frm.recoverToPointInTime(0));

        assertEquals("{a}", db.get("item", 1));
        assertEquals("{b}", db.get("item", 2));
        assertEquals(LogType.ABORT, log.records.getLast().getLogType());
    }

    /** Storage yang gagal sekali pada panggilan undo kedua. */
    private UndoStorage failOnSecond() {
        int[] calls = {0};
        return (table, id, image, lsn) -> {
            if (++calls[0] == 2) {
                throw new IllegalStateException("disk error");
            }
            db.applyUndo(table, id, image, lsn);
        };
    }

    @Test
    void walRecordSerializesRoundTrip() {
        WALRecord r = new WALRecord(3, 1, LogType.UPDATE, 99L, "account", 5, "{a}", null);
        WALRecord back = WALRecord.deserialize(r.serialize());
        assertEquals(r.toString(), back.toString());
        assertEquals(99L, back.getTimestamp());
    }
}
