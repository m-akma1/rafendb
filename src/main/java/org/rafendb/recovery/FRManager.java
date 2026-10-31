package org.rafendb.recovery;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.api.recovery.LogPayload;
import org.rafendb.api.recovery.LoggerErrorException;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.TxStatus;
import org.rafendb.recovery.checkpoint.CheckpointManager;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.restart.CrashRecoveryEngine;
import org.rafendb.recovery.rollback.RollbackManager;
import org.rafendb.recovery.rollback.UndoStorage;

/**
 * Komponen utama Failure Recovery Manager (FRM) Facade milik FR1 sesuai diagram kelas FRM.
 *
 * <p>Mengimplementasikan kontrak publik {@link RecoveryManager} bagi komponen lain seperti Concurrency
 * Control Manager (CCM) dan Query Processor (QP) untuk berinteraksi dengan subsistem pemulihan, penulisan
 * WAL, checkpointing, rollback transaksi, dan pemulihan crash.
 *
 * <p>Thread-safe. Menjamin aturan Write-Ahead Logging (WAL): setiap perubahan data harus dicatat di log
 * sebelum ditulis permanen ke storage.
 */
public class FRManager implements RecoveryManager {

    private final Logger logger;
    private final RollbackManager rollbackManager;
    private final CheckpointManager checkpointManager;
    private final CrashRecoveryEngine crashRecoveryManager;
    private final Map<Integer, TxStatus> txStatuses = new ConcurrentHashMap<>();

    /**
     * Membuat instance FRManager dengan seluruh komponen sub-modul recovery.
     *
     * @param logger logger WAL (FR2), tidak boleh null
     * @param rollbackManager manager rollback dan undo (FR3), tidak boleh null
     * @param checkpointManager manager pembuatan checkpoint (FR4), tidak boleh null
     * @param crashRecoveryManager engine pemulihan crash (FR5), tidak boleh null
     * @throws NullPointerException jika salah satu komponen bernilai null
     */
    public FRManager(
            Logger logger,
            RollbackManager rollbackManager,
            CheckpointManager checkpointManager,
            CrashRecoveryEngine crashRecoveryManager) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.rollbackManager =
                Objects.requireNonNull(rollbackManager, "rollbackManager must not be null");
        this.checkpointManager =
                Objects.requireNonNull(checkpointManager, "checkpointManager must not be null");
        this.crashRecoveryManager =
                Objects.requireNonNull(
                        crashRecoveryManager, "crashRecoveryManager must not be null");
    }

    /**
     * Konstruktor fleksibel untuk inisialisasi praktis dengan logger dan storage adapter.
     *
     * @param logger logger WAL, tidak boleh null
     * @param storage adapter penyimpanan data SM untuk operasi undo/redo, tidak boleh null
     */
    public FRManager(Logger logger, UndoStorage storage) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
        this.rollbackManager = new RollbackManager(logger, storage);
        this.checkpointManager = new CheckpointManager(logger);
        this.crashRecoveryManager = new CrashRecoveryEngine(logger, storage);
    }

    /**
     * Konstruktor praktis yang menghubungkan langsung dengan kontrak resmi {@link StorageManager}.
     *
     * @param logger logger WAL, tidak boleh null
     * @param storageManager implementasi StorageManager untuk recovery apply, tidak boleh null
     */
    public FRManager(Logger logger, StorageManager storageManager) {
        this(
                logger,
                (tableName, idRecord, image, lsn) -> {
                    Rid rid =
                            new Rid(
                                    Math.max(0, (long) (idRecord / 1000)),
                                    Math.max(0, idRecord % 1000));
                    Optional<Row> rowOpt = Optional.empty();
                    storageManager.applyRecovery(tableName, rid, rowOpt, lsn);
                });
    }

    /**
     * Menuliskan record ke log WAL sesuai payload yang diberikan.
     *
     * <p>Meneruskan pencatatan ke {@link Logger}. Mengembalikan nomor urut LSN yang dialokasikan,
     * atau melempar {@link LoggerErrorException} jika penulisan gagal, sehingga komponen pemanggil
     * (CCM/QP) dapat menempelkan LSN ke dirty page dan membatalkan operasi sesuai aturan WAL.
     *
     * @param payload payload data perubahan atau peristiwa transaksi
     * @return nomor LSN yang dialokasikan (positif), atau -1 jika payload null
     * @throws LoggerErrorException jika terjadi kegagalan fatal pada WAL logger
     */
    @Override
    public long writeLog(LogPayload payload) throws LoggerErrorException {
        if (payload == null) {
            return -1;
        }
        try {
            if (payload.opType().isDataChange()) {
                return logger.logDataChange(
                        payload.idTransaction(),
                        payload.opType(),
                        payload.timestamp(),
                        payload.tableName(),
                        payload.idRecord(),
                        payload.beforeVal(),
                        payload.afterVal());
            } else {
                logger.logTransactionEvent(payload.idTransaction(), payload.opType());
                return logger.getCurrentLSN();
            }
        } catch (LoggerErrorException e) {
            throw e;
        } catch (Exception e) {
            throw new LoggerErrorException("Failed to write log record", e);
        }
    }

    /**
     * Mencatat peristiwa dimulainya transaksi baru (BEGIN) ke WAL.
     *
     * @param idTransaction id transaksi yang baru dimulai
     * @return {@code true} jika berhasil dicatat; {@code false} jika gagal
     */
    public boolean beginTransaction(int idTransaction) {
        try {
            logger.logTransactionEvent(idTransaction, LogType.BEGIN);
            txStatuses.put(idTransaction, TxStatus.ACTIVE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Mencatat peristiwa transaksi selesai (COMMIT) dan memastikan record persisten di stable storage.
     *
     * @param idTransaction id transaksi yang di-commit
     * @return {@code true} jika berhasil di-commit dan di-flush; {@code false} jika gagal
     */
    public boolean commitTransaction(int idTransaction) {
        try {
            logger.logTransactionEvent(idTransaction, LogType.COMMIT);
            txStatuses.put(idTransaction, TxStatus.COMMITTED);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Membatalkan seluruh perubahan transaksi (ABORT/ROLLBACK) secara terbalik dan menulis record ABORT.
     *
     * @param idTransaction id transaksi yang akan dibatalkan
     * @return {@code true} jika rollback berhasil dilakukan; {@code false} jika gagal
     */
    public boolean rollbackTransaction(int idTransaction) {
        try {
            boolean success = rollbackManager.rollbackTransaction(idTransaction);
            if (success) {
                txStatuses.put(idTransaction, TxStatus.ABORTED);
            }
            return success;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Memulai proses pembuatan checkpoint sistem.
     *
     * @return {@code true} jika checkpoint berhasil dibuat; {@code false} jika gagal
     */
    public boolean createCheckpoint() {
        try {
            return checkpointManager.createCheckpoint();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Memulihkan basis data ke titik waktu tertentu (Point-In-Time Recovery).
     *
     * @param targetTimestamp waktu tujuan dalam milidetik epoch
     * @return {@code true} jika pemulihan berhasil; {@code false} jika gagal
     */
    public boolean recoverToPointInTime(long targetTimestamp) {
        try {
            rollbackManager.recoverToPointInTime(targetTimestamp);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Overload tanpa argumen untuk pemulihan point-in-time ke titik checkpoint terakhir atau default.
     *
     * @return {@code true} jika pemulihan berhasil; {@code false} jika gagal
     */
    public boolean recoverToPointInTime() {
        return recoverToPointInTime(System.currentTimeMillis());
    }

    /**
     * Menjalankan prosedur pemulihan kegagalan sistem (Crash Recovery) menggunakan protokol ARIES.
     *
     * @return {@code true} jika fase analisis, redo, dan undo berhasil; {@code false} jika gagal
     */
    public boolean recoverFromCrash() {
        try {
            return crashRecoveryManager.recoverFromCrash();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean redo() {
        return crashRecoveryManager != null && crashRecoveryManager.redo();
    }

    @Override
    public boolean undo() {
        return crashRecoveryManager != null && crashRecoveryManager.undo();
    }

    /** @return instance WAL Logger yang digunakan */
    public Logger getLogger() {
        return logger;
    }

    /** @return instance RollbackManager yang digunakan */
    public RollbackManager getRollbackManager() {
        return rollbackManager;
    }

    /** @return instance CheckpointManager yang digunakan */
    public CheckpointManager getCheckpointManager() {
        return checkpointManager;
    }

    /** @return instance CrashRecoveryEngine yang digunakan */
    public CrashRecoveryEngine getCrashRecoveryManager() {
        return crashRecoveryManager;
    }

    @Override
    public TxStatus getTransactionStatus(int idTransaction) {
        TxStatus cached = txStatuses.get(idTransaction);
        if (cached != null) {
            return cached;
        }
        List<WALRecord> txLogs = logger.getLogsByTransaction(idTransaction);
        if (txLogs.isEmpty()) {
            return null;
        }
        for (int i = txLogs.size() - 1; i >= 0; i--) {
            WALRecord rec = txLogs.get(i);
            if (rec.getLogType() == LogType.COMMIT) {
                return TxStatus.COMMITTED;
            }
            if (rec.getLogType() == LogType.ABORT) {
                return TxStatus.ABORTED;
            }
        }
        return TxStatus.ACTIVE;
    }

    @Override
    public void forceThrough(long lsn) {
        logger.forceThrough(lsn);
    }

    @Override
    public void close() {
        try {
            logger.close();
        } catch (Exception ignored) {
        }
    }
}
