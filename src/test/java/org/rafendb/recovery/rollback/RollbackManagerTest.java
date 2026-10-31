package org.rafendb.recovery.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Log palsu di memori, pengganti Logger milik FR2. */
    static final class FakeLog implements RollbackLog {
        final List<WALRecord> records = new ArrayList<>();
        long clock = 1_000_000;

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
            records.add(new WALRecord(records.size() + 1, tx, type, ++clock, null, -1, null, null));
        }

        @Override
        public synchronized long logDataChange(
                int tx, LogType type, long ts, String table, int id, String before, String after) {
            long lsn = records.size() + 1L;
            records.add(new WALRecord(lsn, tx, type, ts, table, id, before, after));
            return lsn;
        }

        @Override
        public void flushAll() {}

        @Override
        public synchronized int getHighestTransactionId() {
            return records.stream().mapToInt(WALRecord::getIdTransaction).max().orElse(0);
        }
    }

    /** Data tabel di memori. */
    static final class FakeStorage implements UndoStorage {
        final Map<String, String> rows = new HashMap<>();

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
        public void applyUndo(String table, int id, String image, long lsn) {
            put(table, id, image);
        }
    }

    FakeLog log;
    FakeStorage db;
    RollbackManager frm;
    long now = 0;

    @BeforeEach
    void setUp() {
        log = new FakeLog();
        db = new FakeStorage();
        frm = new RollbackManager(log, db);
    }

    /** Menjalankan perubahan seperti CCM + SM: tulis log dulu, baru terapkan ke data. */
    void write(int tx, LogType type, String table, int id, String after) {
        log.logDataChange(tx, type, ++now, table, id, db.get(table, id), after);
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
        assertNull(db.get("account", 3));
        assertEquals(LogType.ABORT, log.getLogsByTransaction(1).getLast().getLogType());
    }

    @Test
    void rollbackLeavesOtherCommittedTransactionsAlone() {
        log.logTransactionEvent(1, LogType.BEGIN);
        log.logTransactionEvent(2, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(2, LogType.COMMIT);

        assertTrue(frm.rollbackTransaction(1));

        assertNull(db.get("item", 1));
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
    void transactionWithOnlyBeginCanBeRolledBack() {
        log.logTransactionEvent(1, LogType.BEGIN);

        assertTrue(frm.rollbackTransaction(1));
        assertEquals(LogType.ABORT, log.getLogsByTransaction(1).getLast().getLogType());
    }

    @Test
    void secondRollbackChangesNothing() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");
        frm.rollbackTransaction(1);
        int size = log.getLogsSorted().size();

        assertTrue(frm.rollbackTransaction(1));
        assertEquals(size, log.getLogsSorted().size());
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
        long target = log.getLogsSorted().getLast().getTimestamp();
        log.logDataChange(2, LogType.UPDATE, target + 10, "item", 1, "{a}", "{a2}");
        db.put("item", 1, "{a2}");
        log.logDataChange(2, LogType.INSERT, target + 20, "item", 2, null, "{b}");
        db.put("item", 2, "{b}");

        assertEquals(2, frm.recoverToPointInTime(target));

        assertEquals("{a}", db.get("item", 1));
        assertNull(db.get("item", 2));
    }

    @Test
    void recoverToTransactionDropsTransactionsCommittedLater() {
        log.logTransactionEvent(1, LogType.BEGIN);
        log.logTransactionEvent(2, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}"); // T1 mulai sebelum T2 commit ...
        write(2, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(2, LogType.COMMIT);
        log.logTransactionEvent(1, LogType.COMMIT); // ... tetapi commit sesudahnya

        assertEquals(1, frm.recoverToTransaction(2));

        assertNull(db.get("item", 1));
        assertEquals("{b}", db.get("item", 2));
    }

    @Test
    void recoverToUncommittedTransactionIsRejected() {
        log.logTransactionEvent(1, LogType.BEGIN);
        write(1, LogType.INSERT, "item", 1, "{a}");

        assertThrows(IllegalArgumentException.class, () -> frm.recoverToTransaction(1));
        assertEquals("{a}", db.get("item", 1));
    }

    @Test
    void walRecordSerializesRoundTrip() {
        WALRecord r = new WALRecord(3, 1, LogType.UPDATE, 99L, "account", 5, "{a}", null);
        WALRecord back = WALRecord.deserialize(r.serialize());
        assertEquals(r.toString(), back.toString());
        assertEquals(99L, back.getTimestamp());
    }
}
