package org.rafendb.recovery.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.engine.Engine;
import org.rafendb.recovery.FRManager;
import org.rafendb.recovery.checkpoint.CheckpointManager;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.restart.CrashRecoveryEngine;
import org.rafendb.recovery.rollback.RollbackManager;
import org.rafendb.recovery.rollback.UndoStorage;

/**
 * Driver pengujian independen Failure Recovery Manager sesuai diagram kelas FRM.
 *
 * <p>Mensimulasikan integrasi FRManager dengan StorageManager dan CCManager menggunakan mock memory-store,
 * serta menguji skenario pemulihan crash, undo, redo, transaksi bersisipan (interleaved), dan point-in-time
 * recovery (FR-08 & FR-09).
 */
public class FRManagerDriver {

    private FRManager frm;
    private Path tempLogDir;
    private Path currentLogFile;
    private final MockStorage storage = new MockStorage();

    /**
     * Mock in-memory storage manager untuk keperluan verifikasi pengujian recovery.
     */
    public static class MockStorage implements UndoStorage {
        private final Map<String, String> data = new ConcurrentHashMap<>();

        @Override
        public void applyUndo(String tableName, int idRecord, String image, long lsn) {
            String key = tableName + "#" + idRecord;
            if (image == null) {
                data.remove(key);
            } else {
                data.put(key, image);
            }
        }

        public void put(String tableName, int idRecord, String value) {
            data.put(tableName + "#" + idRecord, value);
        }

        public String get(String tableName, int idRecord) {
            return data.get(tableName + "#" + idRecord);
        }

        public void clear() {
            data.clear();
        }

        public Map<String, String> snapshot() {
            return new HashMap<>(data);
        }
    }

    public FRManagerDriver() {
        initNewEnvironment();
    }

    private void initNewEnvironment() {
        try {
            if (tempLogDir == null) {
                tempLogDir = Files.createTempDirectory("rafendb-frm-test-");
            }
            currentLogFile = tempLogDir.resolve("wal-" + System.nanoTime() + ".log");
            Logger logger = new Logger(currentLogFile);
            RollbackManager rollbackManager = new RollbackManager(logger, storage);
            CheckpointManager checkpointManager = new CheckpointManager(logger);
            CrashRecoveryEngine crashRecoveryEngine = new CrashRecoveryEngine(logger, storage);
            this.frm =
                    new FRManager(logger, rollbackManager, checkpointManager, crashRecoveryEngine);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize driver environment", e);
        }
    }

    /**
     * Mensimulasikan crash sistem secara mendadak dan restart basis data.
     * Menutup logger tanpa buffer flush tambahan, lalu menginisialisasi FRManager baru dari file log yang ada di disk.
     */
    public void simulateCrashAndRestart() {
        try {
            // Tutup channel file tanpa clean flush
            frm.getLogger().close();
            Logger newLogger = new Logger(currentLogFile);
            RollbackManager rollbackManager = new RollbackManager(newLogger, storage);
            CheckpointManager checkpointManager = new CheckpointManager(newLogger);
            CrashRecoveryEngine crashRecoveryEngine = new CrashRecoveryEngine(newLogger, storage);
            this.frm =
                    new FRManager(
                            newLogger, rollbackManager, checkpointManager, crashRecoveryEngine);
        } catch (Exception e) {
            throw new RuntimeException("Failed during simulateCrashAndRestart", e);
        }
    }

    /**
     * Menguji mekanisme Undo untuk transaksi yang dibatalkan (rollback).
     */
    public boolean testUndo() {
        storage.clear();
        initNewEnvironment();

        int tx1 = 1;
        frm.beginTransaction(tx1);

        // Record awal
        storage.put("account", 101, "Alice:100");
        frm.writeLog(tx1, LogType.UPDATE, "account", 101, "Alice:100", "Alice:250");
        storage.put("account", 101, "Alice:250");

        // Rollback Tx1
        boolean rollbackSuccess = frm.rollbackTransaction(tx1);
        String restoredVal = storage.get("account", 101);

        return rollbackSuccess && "Alice:100".equals(restoredVal);
    }

    /**
     * Menguji mekanisme Redo untuk memastikan metode Redo dapat dipanggil dengan sukses.
     */
    public boolean testRedo() {
        initNewEnvironment();
        return frm.redo();
    }

    /**
     * Menguji skenario crash recovery dengan persistensi log nyata ke disk.
     * Mensimulasikan:
     * - T1 menulis data dan berhasil COMMIT.
     * - T2 menulis perubahan kotor (dirty data) namun sistem mengalami CRASH sebelum COMMIT.
     * - Sistem restart dari file WAL dan menjalankan recoverFromCrash (Redo T1, Undo T2).
     */
    public boolean testRecovery() {
        storage.clear();
        initNewEnvironment();

        // 1. Transaksi 1 committed
        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "accounts", 101, null, "User1-Committed");
        storage.put("accounts", 101, "User1-Committed");
        frm.commitTransaction(1);

        // 2. Transaksi 2 belum commit saat crash
        frm.beginTransaction(2);
        frm.writeLog(2, LogType.UPDATE, "accounts", 101, "User1-Committed", "User1-Dirty");
        storage.put("accounts", 101, "User1-Dirty");

        // 3. Simulasi crash mendadak dan restart dari disk
        simulateCrashAndRestart();

        // 4. Jalankan prosedur crash recovery
        boolean recoveryOk = frm.recoverFromCrash();
        String valAfterRecovery = storage.get("accounts", 101);

        return recoveryOk && "User1-Committed".equals(valAfterRecovery);
    }

    /**
     * Menguji pemulihan transaksi bersisipan (interleaved transactions).
     * Transaksi T1, T2, dan T3 berjalan bersamaan:
     * - T1 commit
     * - T2 di-rollback
     * - T3 commit
     */
    public boolean testInterleavedTransactionsRecovery() {
        storage.clear();
        initNewEnvironment();

        frm.beginTransaction(1);
        frm.beginTransaction(2);
        frm.beginTransaction(3);

        // T1 write
        frm.writeLog(1, LogType.INSERT, "ledger", 10, null, "Val-T1");
        storage.put("ledger", 10, "Val-T1");

        // T2 write (interleaved)
        frm.writeLog(2, LogType.INSERT, "ledger", 20, null, "Val-T2");
        storage.put("ledger", 20, "Val-T2");

        // T1 commit
        boolean t1Commit = frm.commitTransaction(1);

        // T3 write
        frm.writeLog(3, LogType.INSERT, "ledger", 30, null, "Val-T3");
        storage.put("ledger", 30, "Val-T3");

        // T3 commit
        boolean t3Commit = frm.commitTransaction(3);

        // T2 rollback
        boolean t2Rollback = frm.rollbackTransaction(2);

        boolean t1Ok = "Val-T1".equals(storage.get("ledger", 10));
        boolean t3Ok = "Val-T3".equals(storage.get("ledger", 30));

        return t1Commit && t3Commit && t2Rollback && t1Ok && t3Ok;
    }

    /**
     * Menguji pemulihan point-in-time ke timestamp sebelum perubahan tertentu terjadi.
     */
    public boolean testPointInTime() {
        storage.clear();
        initNewEnvironment();

        frm.beginTransaction(1);
        frm.writeLog(1, LogType.INSERT, "docs", 1, null, "Version1");
        storage.put("docs", 1, "Version1");
        frm.commitTransaction(1);

        long timestampCutoff = System.currentTimeMillis();
        try {
            Thread.sleep(50); // jeda waktu agar timestamp berikutnya terpisah
        } catch (InterruptedException ignored) {
        }

        frm.beginTransaction(2);
        frm.writeLog(2, LogType.UPDATE, "docs", 1, "Version1", "Version2");
        storage.put("docs", 1, "Version2");
        frm.commitTransaction(2);

        // Pulihkan ke titik waktu sebelum T2
        boolean pitrOk = frm.recoverToPointInTime(timestampCutoff);
        String valAfterPitr = storage.get("docs", 1);

        return pitrOk && "Version1".equals(valAfterPitr);
    }

    /**
     * FR-08 & FR-09: Menguji skenario multi-transaksi dengan {@link StorageManager} asli berbasis
     * berkas, mencakup INSERT/UPDATE/DELETE, rollback, checkpoint, redo (belum flush ke disk), dan
     * undo (terlanjur ditulis ke disk).
     */
    public boolean testRealStorageMultiTransactionCrashRecovery() {
        try {
            Path dir = Files.createTempDirectory("frm-real-driver-");
            Path dataDir = dir.resolve("data");
            Path walPath = dir.resolve("wal.log");
            ConcurrencyControl mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();

            TableSchema schema =
                    new TableSchema(
                            "accounts",
                            List.of(
                                    new Column("id", DataType.INTEGER, 0, false, true),
                                    new Column("owner", DataType.VARCHAR, 50, true, false)));
            Row r1 = new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "Alice")));
            Row r1Updated =
                    new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "Alice-V2")));
            Row r2 = new Row(List.of(Value.ofInt(2), Value.ofText(DataType.VARCHAR, "Bob")));
            Row r3 = new Row(List.of(Value.ofInt(3), Value.ofText(DataType.VARCHAR, "Charlie")));

            Rid rid1;
            Rid rid2;
            Rid rid3;

            try (Logger logger = new Logger(walPath);
                    StorageManager sm = Engine.storage(dataDir, 8, logger);
                    FRManager realFrm = new FRManager(logger, sm, mockCc)) {
                sm.createTable(schema);

                // Tx1: INSERT r1 -> COMMIT, lalu buat checkpoint selesai
                TxId tx1 = new TxId(101);
                realFrm.beginTransaction(tx1);
                rid1 = sm.reserveRid("accounts", r1);
                byte[] r1Bytes = sm.encodeRow("accounts", r1);
                long lsn1 =
                        realFrm.writeLog(
                                new LogPayload(
                                        tx1,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("accounts", rid1),
                                                        Optional.empty(),
                                                        Optional.of(r1Bytes)))));
                sm.insert("accounts", rid1, r1, lsn1);
                realFrm.commitTransaction(tx1);
                realFrm.createCheckpoint();

                // Tx2: INSERT r2 lalu ABORT (rollback)
                TxId tx2 = new TxId(102);
                realFrm.beginTransaction(tx2);
                rid2 = sm.reserveRid("accounts", r2);
                byte[] r2Bytes = sm.encodeRow("accounts", r2);
                long lsn2 =
                        realFrm.writeLog(
                                new LogPayload(
                                        tx2,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("accounts", rid2),
                                                        Optional.empty(),
                                                        Optional.of(r2Bytes)))));
                sm.insert("accounts", rid2, r2, lsn2);
                realFrm.rollbackTransaction(tx2);

                // Tx3: UPDATE r1 -> r1Updated & COMMIT tanpa mem-flush ke SM (simulasi redo)
                TxId tx3 = new TxId(103);
                realFrm.beginTransaction(tx3);
                byte[] r1UpdBytes = sm.encodeRow("accounts", r1Updated);
                realFrm.writeLog(
                        new LogPayload(
                                tx3,
                                org.rafendb.common.log.LogType.UPDATE,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("accounts", rid1),
                                                Optional.of(r1Bytes),
                                                Optional.of(r1UpdBytes)))));
                realFrm.commitTransaction(tx3);

                // Tx4: INSERT r3 ke SM & WAL tanpa COMMIT (simulasi crash uncommitted -> undo)
                TxId tx4 = new TxId(104);
                realFrm.beginTransaction(tx4);
                rid3 = sm.reserveRid("accounts", r3);
                byte[] r3Bytes = sm.encodeRow("accounts", r3);
                long lsn4 =
                        realFrm.writeLog(
                                new LogPayload(
                                        tx4,
                                        org.rafendb.common.log.LogType.INSERT,
                                        System.currentTimeMillis(),
                                        Optional.of(
                                                new ChangeRecord(
                                                        ResourceId.ofRow("accounts", rid3),
                                                        Optional.empty(),
                                                        Optional.of(r3Bytes)))));
                sm.insert("accounts", rid3, r3, lsn4);
            }

            try (Logger restartedLogger = new Logger(walPath);
                    StorageManager restartedSm = Engine.storage(dataDir, 8, restartedLogger);
                    FRManager restartedFrm = new FRManager(restartedLogger, restartedSm, mockCc)) {
                boolean recovered = restartedFrm.recoverFromCrash();
                Optional<Row> actualR1 = restartedSm.fetch("accounts", rid1).map(StoredRow::row);
                Optional<Row> actualR2 = restartedSm.fetch("accounts", rid2).map(StoredRow::row);
                Optional<Row> actualR3 = restartedSm.fetch("accounts", rid3).map(StoredRow::row);

                boolean ok =
                        recovered
                                && Optional.of(r1Updated).equals(actualR1)
                                && actualR2.isEmpty()
                                && actualR3.isEmpty();
                System.out.println(
                        "  [DETAIL] Expected: r1="
                                + r1Updated
                                + ", r2=empty, r3=empty | Actual: r1="
                                + actualR1.orElse(null)
                                + ", r2="
                                + actualR2.orElse(null)
                                + ", r3="
                                + actualR3.orElse(null));
                return ok;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Helper untuk simulasi crash proses OS nyata (FR-08): menulis transaksi committed dan
     * uncommitted ke StorageManager + WAL asli, lalu menunggu dihentikan paksa tanpa graceful
     * shutdown.
     */
    public static void runCrashChildSimulation(Path dataDir, Path walPath) throws Exception {
        ConcurrencyControl mockCc = new org.rafendb.mock.concurrency.MockConcurrencyControl();
        Logger logger = new Logger(walPath);
        StorageManager sm = Engine.storage(dataDir, 8, logger);
        FRManager realFrm = new FRManager(logger, sm, mockCc);

        sm.createTable(
                new TableSchema(
                        "items",
                        List.of(
                                new Column("id", DataType.INTEGER, 0, false, true),
                                new Column("name", DataType.VARCHAR, 50, true, false))));

        Row committedRow =
                new Row(List.of(Value.ofInt(1), Value.ofText(DataType.VARCHAR, "CommittedItem")));
        Row uncommittedRow =
                new Row(List.of(Value.ofInt(2), Value.ofText(DataType.VARCHAR, "UncommittedItem")));

        // Tx1: COMMIT (harus bertahan pasca-crash)
        TxId tx1 = new TxId(1);
        realFrm.beginTransaction(tx1);
        Rid rid1 = sm.reserveRid("items", committedRow);
        byte[] cBytes = sm.encodeRow("items", committedRow);
        long lsn1 =
                realFrm.writeLog(
                        new LogPayload(
                                tx1,
                                org.rafendb.common.log.LogType.INSERT,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("items", rid1),
                                                Optional.empty(),
                                                Optional.of(cBytes)))));
        sm.insert("items", rid1, committedRow, lsn1);
        realFrm.commitTransaction(tx1);

        // Tx2: ACTIVE dan sudah menulis ke disk (flush) tanpa COMMIT -> wajib di-UNDO saat restart
        TxId tx2 = new TxId(2);
        realFrm.beginTransaction(tx2);
        Rid rid2 = sm.reserveRid("items", uncommittedRow);
        byte[] uBytes = sm.encodeRow("items", uncommittedRow);
        long lsn2 =
                realFrm.writeLog(
                        new LogPayload(
                                tx2,
                                org.rafendb.common.log.LogType.INSERT,
                                System.currentTimeMillis(),
                                Optional.of(
                                        new ChangeRecord(
                                                ResourceId.ofRow("items", rid2),
                                                Optional.empty(),
                                                Optional.of(uBytes)))));
        sm.insert("items", rid2, uncommittedRow, lsn2);
        sm.flush();

        System.out.println(
                "READY_FOR_CRASH:"
                        + rid1.block()
                        + ","
                        + rid1.slot()
                        + ";"
                        + rid2.block()
                        + ","
                        + rid2.slot());
        System.out.flush();
        Thread.sleep(60_000L);
    }

    /**
     * Menjalankan seluruh rangkaian tes driver dan menampilkan laporan hasil.
     *
     * @return true jika seluruh pengujian berhasil
     */
    public boolean runAllTests() {
        System.out.println("==================================================");
        System.out.println("       RUNNING FRM INTEGRATION TEST SUITE         ");
        System.out.println("==================================================");

        boolean undoPassed = testUndo();
        System.out.println("[TEST] Undo Mechanism: " + (undoPassed ? "PASSED" : "FAILED"));

        boolean redoPassed = testRedo();
        System.out.println("[TEST] Redo Mechanism: " + (redoPassed ? "PASSED" : "FAILED"));

        boolean recoveryPassed = testRecovery();
        System.out.println("[TEST] Crash Recovery: " + (recoveryPassed ? "PASSED" : "FAILED"));

        boolean interleavedPassed = testInterleavedTransactionsRecovery();
        System.out.println(
                "[TEST] Interleaved Transactions: " + (interleavedPassed ? "PASSED" : "FAILED"));

        boolean pitrPassed = testPointInTime();
        System.out.println("[TEST] Point-In-Time Recovery: " + (pitrPassed ? "PASSED" : "FAILED"));

        boolean realStoragePassed = testRealStorageMultiTransactionCrashRecovery();
        System.out.println(
                "[TEST] Real Storage Multi-Tx Recovery: "
                        + (realStoragePassed ? "PASSED" : "FAILED"));

        System.out.println("==================================================");
        boolean allPassed =
                undoPassed
                        && redoPassed
                        && recoveryPassed
                        && interleavedPassed
                        && pitrPassed
                        && realStoragePassed;
        System.out.println(
                "TOTAL RESULT: " + (allPassed ? "ALL TESTS PASSED!" : "SOME TESTS FAILED"));
        System.out.println("==================================================");
        return allPassed;
    }

    public FRManager getFRManager() {
        return frm;
    }

    public Path getCurrentLogFile() {
        return currentLogFile;
    }

    public MockStorage getStorage() {
        return storage;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--simulate-crash-child".equals(args[0])) {
            runCrashChildSimulation(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        FRManagerDriver driver = new FRManagerDriver();
        boolean ok = driver.runAllTests();
        if (!ok) {
            System.exit(1);
        }
    }
}
