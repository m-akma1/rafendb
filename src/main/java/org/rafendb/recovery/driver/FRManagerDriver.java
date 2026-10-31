package org.rafendb.recovery.driver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.api.recovery.LogPayload;
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
     * Menutup logger tanpa buffer flush tambahan, lalu menginisialisasi FRManager baru dari file log yang ada.
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
        frm.writeLog(
                LogPayload.ofDataChange(
                        tx1, LogType.UPDATE, "account", 101, "Alice:100", "Alice:250"));
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
     * Menguji skenario crash recovery.
     */
    public boolean testRecovery() {
        initNewEnvironment();
        return frm.recoverFromCrash();
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
        frm.writeLog(LogPayload.ofDataChange(1, LogType.INSERT, "ledger", 10, null, "Val-T1"));
        storage.put("ledger", 10, "Val-T1");

        // T2 write (interleaved)
        frm.writeLog(LogPayload.ofDataChange(2, LogType.INSERT, "ledger", 20, null, "Val-T2"));
        storage.put("ledger", 20, "Val-T2");

        // T1 commit
        boolean t1Commit = frm.commitTransaction(1);

        // T3 write
        frm.writeLog(LogPayload.ofDataChange(3, LogType.INSERT, "ledger", 30, null, "Val-T3"));
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
        frm.writeLog(LogPayload.ofDataChange(1, LogType.INSERT, "docs", 1, null, "Version1"));
        storage.put("docs", 1, "Version1");
        frm.commitTransaction(1);

        long timestampCutoff = System.currentTimeMillis();
        try {
            Thread.sleep(50); // jeda waktu agar timestamp berikutnya terpisah
        } catch (InterruptedException ignored) {
        }

        frm.beginTransaction(2);
        frm.writeLog(LogPayload.ofDataChange(2, LogType.UPDATE, "docs", 1, "Version1", "Version2"));
        storage.put("docs", 1, "Version2");
        frm.commitTransaction(2);

        // Pulihkan ke titik waktu sebelum T2
        boolean pitrOk = frm.recoverToPointInTime(timestampCutoff);
        String valAfterPitr = storage.get("docs", 1);

        return pitrOk && "Version1".equals(valAfterPitr);
    }

    /**
     * Menjalankan seluruh rangkaian tes driver dan menampilkan laporan hasil.
     */
    public void runAllTests() {
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

        System.out.println("==================================================");
        boolean allPassed =
                undoPassed && redoPassed && recoveryPassed && interleavedPassed && pitrPassed;
        System.out.println(
                "TOTAL RESULT: " + (allPassed ? "ALL TESTS PASSED!" : "SOME TESTS FAILED"));
        System.out.println("==================================================");
    }

    public static void main(String[] args) {
        FRManagerDriver driver = new FRManagerDriver();
        driver.runAllTests();
    }
}
