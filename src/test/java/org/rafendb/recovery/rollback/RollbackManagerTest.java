package org.rafendb.recovery.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.WALRecord;

class RollbackManagerTest {

    /** Jam palsu bersama untuk log dan manager, agar timestamp bisa diatur. */
    long now = 1_000;

    /** Log palsu di memori, pengganti Logger milik FR2. */
    final class FakeLog implements RollbackLog {
        final List<WALRecord> records = new ArrayList<>();
        int flushes;

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
            records.add(new WALRecord(records.size() + 1, tx, type, now, null, -1, null, null));
        }

        @Override
        public synchronized long logDataChange(
                int tx, LogType type, long ts, String table, int id, String before, String after) {
            long lsn = records.size() + 1L;
            records.add(new WALRecord(lsn, tx, type, ts, table, id, before, after));
            return lsn;
        }

        @Override
        public synchronized void flushAll() {
            flushes++;
        }

        @Override
        public synchronized int getHighestTransactionId() {
            return records.stream().mapToInt(WALRecord::getIdTransaction).max().orElse(0);
        }

        synchronized int size() {
            return records.size();
        }
    }

    /** Data tabel di memori; bisa diatur agar gagal pada pemanggilan ke-n. */
    static final class FakeStorage implements UndoStorage {
        final Map<String, String> rows = new HashMap<>();
        final List<Long> appliedLsns = new ArrayList<>();
        int failOnCall = -1;
        int calls;

        synchronized void put(String table, int id, String image) {
            if (image == null) {
                rows.remove(table + "#" + id);
            } else {
                rows.put(table + "#" + id, image);
            }
        }

        synchronized String get(String table, int id) {
            return rows.get(table + "#" + id);
        }

        synchronized Map<String, String> snapshot() {
            return new HashMap<>(rows);
        }

        @Override
        public synchronized void applyUndo(String table, int id, String image, long lsn) {
            if (++calls == failOnCall) {
                throw new IllegalStateException("disk full");
            }
            appliedLsns.add(lsn);
            put(table, id, image);
        }
    }

    FakeLog log;
    FakeStorage db;
    RollbackManager frm;

    @BeforeEach
    void setUp() {
        log = new FakeLog();
        db = new FakeStorage();
        frm = new RollbackManager(log, db, () -> now);
    }

    void begin(int tx) {
        now++;
        log.logTransactionEvent(tx, LogType.BEGIN);
    }

    void commit(int tx) {
        now++;
        log.logTransactionEvent(tx, LogType.COMMIT);
    }

    /** Menjalankan perubahan seperti CCM + SM: tulis log dulu, baru terapkan ke data. */
    void write(int tx, LogType type, String table, int id, String after) {
        now++;
        log.logDataChange(tx, type, now, table, id, db.get(table, id), after);
        db.put(table, id, after);
    }

    // --------------------------------------------------------------------------------------
    // FR-04
    // --------------------------------------------------------------------------------------

    @Test
    void rollbackUndoesInsertUpdateDeleteLatestFirstIncludingRepeatedRecord() {
        db.put("account", 1, "{bal:10}");
        db.put("account", 2, "{bal:20}");
        begin(1);
        write(1, LogType.UPDATE, "account", 1, "{bal:5}");
        write(1, LogType.UPDATE, "account", 1, "{bal:0}");
        write(1, LogType.DELETE, "account", 2, null);
        write(1, LogType.INSERT, "account", 3, "{bal:99}");
        write(1, LogType.UPDATE, "account", 3, "{bal:98}");

        assertEquals(RollbackStatus.ROLLED_BACK, frm.rollback(1));

        assertEquals("{bal:10}", db.get("account", 1));
        assertEquals("{bal:20}", db.get("account", 2));
        assertNull(db.get("account", 3));
        assertEquals(LogType.ABORT, log.getLogsByTransaction(1).getLast().getLogType());
    }

    @Test
    void eachUndoIsLoggedAsInverseChangeBeforeStorageIsTouched() {
        db.put("item", 1, "{a}");
        begin(1);
        write(1, LogType.UPDATE, "item", 1, "{b}");
        write(1, LogType.INSERT, "item", 2, "{c}");
        int before = log.size();

        frm.rollback(1);

        List<WALRecord> added = log.getLogsSorted().subList(before, log.size());
        assertEquals(LogType.DELETE, added.get(0).getLogType()); // undo INSERT item#2
        assertEquals("{c}", added.get(0).getBeforeValue());
        assertNull(added.get(0).getAfterValue());
        assertEquals(LogType.UPDATE, added.get(1).getLogType()); // undo UPDATE item#1
        assertEquals("{b}", added.get(1).getBeforeValue());
        assertEquals("{a}", added.get(1).getAfterValue());
        assertEquals(LogType.ABORT, added.get(2).getLogType());
        // Storage menerima LSN record kompensasi yang sudah ditulis (WAL).
        assertEquals(List.of(added.get(0).getLSN(), added.get(1).getLSN()), db.appliedLsns);
        assertTrue(log.flushes > 0);
    }

    @Test
    void rollbackLeavesOtherTransactionsAlone() {
        begin(1);
        begin(2);
        write(1, LogType.INSERT, "item", 1, "{a}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        commit(2);

        assertTrue(frm.rollbackTransaction(1));

        assertNull(db.get("item", 1));
        assertEquals("{b}", db.get("item", 2));
    }

    @Test
    void committedAndUnknownTransactionsGetClearStatusAndNothingChanges() {
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{a}");
        commit(1);
        int size = log.size();

        assertEquals(RollbackStatus.ALREADY_COMMITTED, frm.rollback(1));
        assertEquals(RollbackStatus.UNKNOWN_TRANSACTION, frm.rollback(42));
        assertFalse(frm.rollbackTransaction(1));
        assertEquals("{a}", db.get("item", 1));
        assertEquals(size, log.size());
    }

    @Test
    void transactionWithOnlyBeginCanBeRolledBack() {
        begin(1);

        assertEquals(RollbackStatus.ROLLED_BACK, frm.rollback(1));
        assertEquals(LogType.ABORT, log.getLogsByTransaction(1).getLast().getLogType());
    }

    @Test
    void secondRollbackChangesNothing() {
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{a}");
        frm.rollback(1);
        int size = log.size();
        int calls = db.calls;

        assertEquals(RollbackStatus.ALREADY_ABORTED, frm.rollback(1));
        assertEquals(size, log.size());
        assertEquals(calls, db.calls);
    }

    @Test
    void failedRollbackWritesNoAbortAndRetryResumesWithoutDuplicateCompensation() {
        db.put("item", 1, "{a}");
        begin(1);
        write(1, LogType.UPDATE, "item", 1, "{b}");
        write(1, LogType.INSERT, "item", 2, "{c}");
        write(1, LogType.UPDATE, "item", 1, "{d}");
        db.failOnCall = 2;

        assertThrows(IllegalStateException.class, () -> frm.rollback(1));
        assertFalse(
                log.getLogsByTransaction(1).stream()
                        .anyMatch(r -> r.getLogType() == LogType.ABORT));

        assertEquals(RollbackStatus.ROLLED_BACK, frm.rollback(1));

        assertEquals("{a}", db.get("item", 1));
        assertNull(db.get("item", 2));
        long compensations =
                log.getLogsByTransaction(1).stream()
                        .filter(r -> r.getLogType().isDataChange())
                        .count();
        assertEquals(6, compensations); // 3 perubahan asli + tepat 3 kompensasi
    }

    @Test
    void interruptedRollbackIsCorrectlyUndoneByReplayingWholeTransactionBackwards() {
        // Simulasi crash di tengah rollback: crash recovery membatalkan semua record T1, termasuk
        // record kompensasi, dari yang terbaru.
        db.put("item", 1, "{a}");
        begin(1);
        write(1, LogType.UPDATE, "item", 1, "{b}");
        write(1, LogType.UPDATE, "item", 1, "{c}");
        db.failOnCall = 2;
        assertThrows(IllegalStateException.class, () -> frm.rollback(1));

        List<WALRecord> t1 = log.getLogsByTransaction(1);
        for (WALRecord r : t1.reversed()) {
            if (r.getLogType().isDataChange()) {
                db.put(r.getTableName(), r.getIdRecord(), r.getBeforeValue());
            }
        }
        assertEquals("{a}", db.get("item", 1));
    }

    @Test
    void concurrentRollbacksOfDifferentTransactionsAreAllCorrect() throws Exception {
        int n = 8;
        for (int tx = 1; tx <= n; tx++) {
            begin(tx);
            db.put("item", tx, "{orig" + tx + "}");
            write(tx, LogType.UPDATE, "item", tx, "{x}");
            write(tx, LogType.INSERT, "item", 100 + tx, "{y}");
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<RollbackStatus>> results = new ArrayList<>();
            for (int tx = 1; tx <= n; tx++) {
                int id = tx;
                results.add(pool.submit(() -> frm.rollback(id)));
                results.add(pool.submit(() -> frm.rollback(id))); // permintaan ganda
            }
            int rolledBack = 0;
            for (Future<RollbackStatus> f : results) {
                RollbackStatus s = f.get();
                assertTrue(s.isAborted());
                if (s == RollbackStatus.ROLLED_BACK) {
                    rolledBack++;
                }
            }
            assertEquals(n, rolledBack);
        } finally {
            pool.shutdown();
        }
        for (int tx = 1; tx <= n; tx++) {
            assertEquals("{orig" + tx + "}", db.get("item", tx));
            assertNull(db.get("item", 100 + tx));
        }
    }

    // --------------------------------------------------------------------------------------
    // FR-05
    // --------------------------------------------------------------------------------------

    @Test
    void emptyLogRecoveryIsANoOp() {
        assertEquals(0, frm.recoverToPointInTime(now));
    }

    @Test
    void recoverToPointInTimeKeepsOnlyTransactionsCommittedByThen() {
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{a}");
        commit(1);
        long target = now;
        begin(2);
        write(2, LogType.UPDATE, "item", 1, "{a2}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        commit(2);

        assertEquals(2, frm.recoverToPointInTime(target));

        assertEquals("{a}", db.get("item", 1));
        assertNull(db.get("item", 2));
    }

    @Test
    void transactionStartedBeforeTargetButCommittedAfterIsExcluded() {
        begin(1);
        begin(2);
        write(2, LogType.INSERT, "item", 2, "{b}");
        write(1, LogType.INSERT, "item", 1, "{a}");
        commit(1);
        long target = now;
        write(2, LogType.INSERT, "item", 3, "{c}");
        commit(2);

        frm.recoverToPointInTime(target);

        assertEquals("{a}", db.get("item", 1));
        assertNull(db.get("item", 2));
        assertNull(db.get("item", 3));
    }

    @Test
    void commitsWithTheSameTimestampAsTargetAreIncluded() {
        begin(1);
        begin(2);
        write(1, LogType.INSERT, "item", 1, "{a}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        log.logTransactionEvent(1, LogType.COMMIT); // dua commit dengan waktu yang sama
        log.logTransactionEvent(2, LogType.COMMIT);
        long target = now;
        begin(3);
        write(3, LogType.INSERT, "item", 3, "{c}");
        commit(3);

        frm.recoverToPointInTime(target);

        assertEquals("{a}", db.get("item", 1));
        assertEquals("{b}", db.get("item", 2));
        assertNull(db.get("item", 3));
    }

    @Test
    void abortedTransactionsAreNotUndoneAgain() {
        db.put("item", 1, "{a}");
        begin(1);
        write(1, LogType.UPDATE, "item", 1, "{b}");
        frm.rollback(1); // item#1 kembali {a}
        begin(2);
        write(2, LogType.UPDATE, "item", 1, "{c}");
        commit(2);
        long target = now;
        begin(3);
        write(3, LogType.INSERT, "item", 9, "{z}");
        commit(3);

        frm.recoverToPointInTime(target);

        assertEquals("{c}", db.get("item", 1)); // bukan {a}: T1 tidak dibatalkan dua kali
        assertNull(db.get("item", 9));
    }

    @Test
    void recoverToTransactionUsesCommitOrderNotIdOrder() {
        begin(5);
        begin(2);
        write(5, LogType.INSERT, "item", 1, "{a}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        commit(5);
        commit(2);
        begin(3);
        write(3, LogType.INSERT, "item", 3, "{c}");
        commit(3);

        assertEquals(2, frm.recoverToTransaction(5));

        assertEquals("{a}", db.get("item", 1));
        assertNull(db.get("item", 2)); // ID lebih kecil, tetapi commit sesudah T5
        assertNull(db.get("item", 3));
    }

    @Test
    void invalidTargetsAreRejectedBeforeAnythingChanges() {
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{a}");
        commit(1);
        begin(2);
        write(2, LogType.INSERT, "item", 2, "{b}");
        // T2 masih berjalan.
        Map<String, String> data = db.snapshot();
        int size = log.size();
        long first = log.getLogsSorted().getFirst().getTimestamp();

        assertThrows(IllegalArgumentException.class, () -> frm.recoverToPointInTime(now + 1));
        assertThrows(IllegalArgumentException.class, () -> frm.recoverToPointInTime(first - 1));
        assertThrows(IllegalArgumentException.class, () -> frm.recoverToTransaction(2));
        assertThrows(IllegalArgumentException.class, () -> frm.recoverToTransaction(99));
        assertThrows(IllegalStateException.class, () -> frm.recoverToPointInTime(now));

        assertEquals(data, db.snapshot());
        assertEquals(size, log.size());
    }

    @Test
    void recoveryIsLoggedAsCommittedSystemTransactionSoItSurvivesRestart() {
        Map<String, String> initial = db.snapshot();
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{a}");
        commit(1);
        long target = now;
        begin(2);
        write(2, LogType.UPDATE, "item", 1, "{a2}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        commit(2);

        frm.recoverToPointInTime(target);
        Map<String, String> afterRecovery = db.snapshot();

        List<WALRecord> all = log.getLogsSorted();
        int systemTx = all.getLast().getIdTransaction();
        assertEquals(3, systemTx);
        assertEquals(LogType.COMMIT, all.getLast().getLogType());

        // Restart: mulai dari keadaan awal lalu redo semua transaksi committed dari log.
        Set<Integer> committed = new HashSet<>();
        for (WALRecord r : all) {
            if (r.getLogType() == LogType.COMMIT) {
                committed.add(r.getIdTransaction());
            }
        }
        FakeStorage restarted = new FakeStorage();
        restarted.rows.putAll(initial);
        for (WALRecord r : all) {
            if (r.getLogType().isDataChange() && committed.contains(r.getIdTransaction())) {
                restarted.put(r.getTableName(), r.getIdRecord(), r.getAfterValue());
            }
        }
        assertEquals(afterRecovery, restarted.snapshot());
    }

    @Test
    void successiveRecoveriesStayCorrect() {
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{v1}");
        commit(1);
        long t1 = now;
        begin(2);
        write(2, LogType.UPDATE, "item", 1, "{v2}");
        commit(2);
        long t2 = now;
        now += 10;

        frm.recoverToPointInTime(t1);
        assertEquals("{v1}", db.get("item", 1));

        now += 10;
        frm.recoverToPointInTime(t2); // membatalkan pemulihan pertama
        assertEquals("{v2}", db.get("item", 1));
    }

    @Test
    void storageFailureDuringRecoveryRestoresPreviousState() {
        begin(1);
        write(1, LogType.INSERT, "item", 1, "{a}");
        commit(1);
        long target = now;
        begin(2);
        write(2, LogType.UPDATE, "item", 1, "{a2}");
        write(2, LogType.INSERT, "item", 2, "{b}");
        write(2, LogType.INSERT, "item", 3, "{c}");
        commit(2);
        Map<String, String> data = db.snapshot();
        db.failOnCall = 3;

        assertThrows(IllegalStateException.class, () -> frm.recoverToPointInTime(target));

        assertEquals(data, db.snapshot());
        WALRecord last = log.getLogsSorted().getLast();
        assertEquals(3, last.getIdTransaction());
        assertEquals(LogType.ABORT, last.getLogType());
    }

    @Test
    void walRecordSerializesRoundTrip() {
        WALRecord r = new WALRecord(3, 1, LogType.UPDATE, 99L, "account", 5, "{a}", null);
        WALRecord back = WALRecord.deserialize(r.serialize());
        assertEquals(r.toString(), back.toString());
        assertEquals(99L, back.getTimestamp());
    }
}
