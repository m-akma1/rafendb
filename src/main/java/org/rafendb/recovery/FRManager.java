package org.rafendb.recovery;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.api.concurrency.ConcurrencyControl;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.api.storage.StorageRecovery;
import org.rafendb.common.error.RecoveryInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.log.LogPayload;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.RecoveryInfo;
import org.rafendb.common.txn.ResourceId;
import org.rafendb.common.txn.TxId;
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
    private final StorageRecovery storageManager;
    private final boolean encodeBinaryImages;
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
        this.storageManager = null;
        this.encodeBinaryImages = false;
    }

    /**
     * Konstruktor fleksibel untuk inisialisasi praktis dengan logger dan storage adapter.
     *
     * @param logger logger WAL, tidak boleh null
     * @param storage adapter penyimpanan data SM untuk operasi undo/redo, tidak boleh null
     */
    public FRManager(Logger logger, UndoStorage storage) {
        this(logger, storage, null, false);
    }

    private FRManager(
            Logger logger,
            UndoStorage storage,
            StorageRecovery storageManager,
            boolean encodeBinaryImages) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        Objects.requireNonNull(storage, "storage must not be null");
        this.rollbackManager = new RollbackManager(logger, storage);
        this.checkpointManager =
                storageManager != null
                        ? new CheckpointManager(logger, storageManager)
                        : new CheckpointManager(logger);
        this.crashRecoveryManager = new CrashRecoveryEngine(logger, storage);
        this.storageManager = storageManager;
        this.encodeBinaryImages = encodeBinaryImages;
    }

    /**
     * Konstruktor yang menghubungkan langsung dengan kontrak resmi {@link StorageRecovery} untuk
     * recovery apply dan flush checkpoint. Untuk quiescent checkpoint, teruskan {@link
     * ConcurrencyControl} ke {@link #createCheckpoint(ConcurrencyControl)}.
     *
     * @param logger logger WAL, tidak boleh null
     * @param storageManager implementasi StorageRecovery untuk recovery apply &amp; flush, tidak boleh
     *     null
     */
    public FRManager(Logger logger, StorageRecovery storageManager) {
        this(
                logger,
                storageAdapter(storageManager),
                Objects.requireNonNull(storageManager, "storageManager must not be null"),
                true);
    }

    private static final String BASE64_IMAGE_PREFIX = "B64:";

    static long encodeTableGeneration(UUID generation) {
        if (generation == null) {
            return 0L;
        }
        long encoded = generation.getMostSignificantBits() ^ generation.getLeastSignificantBits();
        return encoded != 0L ? encoded : 1L;
    }

    private static long resolveTableGeneration(StorageRecovery storageManager, String tableName) {
        if (storageManager == null
                || tableName == null
                || !storageManager.listTables().contains(tableName)) {
            return 0L;
        }
        try {
            return encodeTableGeneration(storageManager.getTableGeneration(tableName));
        } catch (StorageRuntimeException ignored) {
            return 0L;
        }
    }

    /**
     * Membuat adapter {@link UndoStorage} yang men-decode image biner memakai {@link
     * StorageRecovery#decodeRow(String, byte[])} lalu meneruskan ke {@link
     * StorageRecovery#applyRecovery(String, Rid, Optional, long)}.
     *
     * @param storageManager implementasi StorageRecovery, tidak boleh null
     * @return adapter UndoStorage yang siap dipakai oleh RollbackManager dan CrashRecoveryEngine
     */
    public static UndoStorage storageAdapter(StorageRecovery storageManager) {
        return new StorageManagerUndoAdapter(storageManager);
    }

    /**
     * Adapter eksplisit yang menghubungkan antarmuka {@link UndoStorage} milik FRM ke
     * implementasi resmi {@link StorageRecovery}.
     */
    public static final class StorageManagerUndoAdapter implements UndoStorage {
        private final StorageRecovery storageManager;

        public StorageManagerUndoAdapter(StorageRecovery storageManager) {
            this.storageManager =
                    Objects.requireNonNull(storageManager, "storageManager must not be null");
        }

        @Override
        public void applyUndo(String tableName, int idRecord, String image, long lsn) {
            Rid rid = idRecord >= 0 ? new Rid((long) idRecord / 1000, idRecord % 1000) : null;
            applyUndo(tableName, 0L, rid, image, lsn);
        }

        @Override
        public void applyUndo(
                String tableName, long tableGeneration, Rid rid, String image, long lsn) {
            if (tableName == null || !storageManager.listTables().contains(tableName)) {
                return;
            }
            try {
                UUID currentGen = storageManager.getTableGeneration(tableName);
                if (tableGeneration != 0L && tableGeneration != encodeTableGeneration(currentGen)) {
                    return;
                }
            } catch (StorageRuntimeException ignored) {
                return;
            }
            try {
                Optional<Row> rowOpt;
                if (image == null) {
                    rowOpt = Optional.empty();
                } else {
                    byte[] rawBytes = decodeImageBytes(image);
                    rowOpt = Optional.of(storageManager.decodeRow(tableName, rawBytes));
                }
                storageManager.applyRecovery(tableName, rid, rowOpt, lsn);
            } catch (Exception e) {
                throw new RuntimeException("StorageManager applyRecovery failed", e);
            }
        }

        public StorageRecovery getStorageManager() {
            return storageManager;
        }
    }

    private static String encodeImageBytes(Optional<byte[]> image, boolean base64) {
        if (base64) {
            return image.map(b -> BASE64_IMAGE_PREFIX + Base64.getEncoder().encodeToString(b))
                    .orElse(null);
        }
        return image.map(b -> new String(b, StandardCharsets.UTF_8)).orElse(null);
    }

    private static byte[] decodeImageBytes(String image) {
        if (image.startsWith(BASE64_IMAGE_PREFIX)) {
            return Base64.getDecoder().decode(image.substring(BASE64_IMAGE_PREFIX.length()));
        }
        return image.getBytes(StandardCharsets.UTF_8);
    }

    private static int toTxIdInt(TxId tx) {
        Objects.requireNonNull(tx, "tx must not be null");
        try {
            return Math.toIntExact(tx.value());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Transaction ID exceeds 32-bit int range supported by WAL: " + tx.value(), e);
        }
    }

    private void updateCachedTxStatus(int txId, LogType logType) {
        switch (logType) {
            case BEGIN -> txStatuses.put(txId, TxStatus.ACTIVE);
            case COMMIT -> txStatuses.put(txId, TxStatus.COMMITTED);
            case ABORT -> txStatuses.put(txId, TxStatus.ABORTED);
            default -> {}
        }
    }

    @Override
    public long writeLog(org.rafendb.common.log.LogPayload payload)
            throws RecoveryInternalException {
        if (payload == null) {
            throw new NullPointerException("payload must not be null");
        }
        int txId = toTxIdInt(payload.tx());
        try {
            LogType logType = LogType.valueOf(payload.type().name());
            if (payload.change().isPresent()) {
                var change = payload.change().get();
                String table = change.resource().table();
                Rid rid = change.resource().rid();
                String beforeStr = encodeImageBytes(change.beforeImage(), encodeBinaryImages);
                String afterStr = encodeImageBytes(change.afterImage(), encodeBinaryImages);
                if (rid != null) {
                    long tableGeneration = resolveTableGeneration(storageManager, table);
                    return logger.logDataChange(
                            txId,
                            logType,
                            payload.timestamp(),
                            table,
                            tableGeneration,
                            rid,
                            beforeStr,
                            afterStr);
                }
                return logger.logDataChange(
                        txId, logType, payload.timestamp(), table, -1, beforeStr, afterStr);
            } else {
                long lsn = logger.logTransactionEvent(txId, logType, payload.timestamp());
                updateCachedTxStatus(txId, logType);
                return lsn;
            }
        } catch (Exception e) {
            throw new RecoveryInternalException("Failed to write log record", e);
        }
    }

    /**
     * Overload praktis untuk mencatat perubahan data langsung ke WAL logger dengan timestamp saat ini.
     *
     * @param idTransaction id transaksi
     * @param logType jenis operasi
     * @param tableName nama tabel
     * @param idRecord id record
     * @param beforeVal nilai sebelum
     * @param afterVal nilai sesudah
     * @return nomor LSN yang dialokasikan
     * @throws RecoveryInternalException jika terjadi kegagalan fatal pada WAL logger
     */
    public long writeLog(
            int idTransaction,
            LogType logType,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal)
            throws RecoveryInternalException {
        return writeLog(
                idTransaction,
                logType,
                System.currentTimeMillis(),
                tableName,
                idRecord,
                beforeVal,
                afterVal);
    }

    /**
     * Overload praktis untuk mencatat perubahan data langsung ke WAL logger dengan timestamp tertentu.
     *
     * @param idTransaction id transaksi
     * @param logType jenis operasi
     * @param timestamp timestamp epoch milidetik
     * @param tableName nama tabel
     * @param idRecord id record
     * @param beforeVal nilai sebelum
     * @param afterVal nilai sesudah
     * @return nomor LSN yang dialokasikan
     * @throws RecoveryInternalException jika terjadi kegagalan fatal pada WAL logger
     */
    public long writeLog(
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal)
            throws RecoveryInternalException {
        Rid rid = idRecord >= 0 ? new Rid((long) idRecord / 1000, idRecord % 1000) : null;
        ChangeRecord change =
                new ChangeRecord(
                        rid != null
                                ? ResourceId.ofRow(tableName, rid)
                                : ResourceId.ofTable(tableName),
                        Optional.ofNullable(
                                beforeVal != null
                                        ? beforeVal.getBytes(StandardCharsets.UTF_8)
                                        : null),
                        Optional.ofNullable(
                                afterVal != null
                                        ? afterVal.getBytes(StandardCharsets.UTF_8)
                                        : null));
        org.rafendb.common.log.LogType commonType =
                org.rafendb.common.log.LogType.valueOf(logType.name());
        LogPayload payload =
                new LogPayload(new TxId(idTransaction), commonType, timestamp, Optional.of(change));
        return writeLog(payload);
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
            logger.flushAll();
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
     * Memulai proses pembuatan quiescent checkpoint dengan menyalurkan {@link ConcurrencyControl}
     * untuk menahan transaksi baru dan menunggu transaksi aktif selesai.
     *
     * @param concurrencyControl pengelola concurrency opsional untuk quiescent checkpoint
     * @return {@code true} jika checkpoint berhasil dibuat; {@code false} jika gagal
     */
    @Override
    public boolean createCheckpoint(ConcurrencyControl concurrencyControl) {
        try {
            return checkpointManager.createCheckpoint(concurrencyControl);
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
        } finally {
            txStatuses.clear();
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
     * Memulihkan basis data ke keadaan tepat setelah record COMMIT transaksi tertentu.
     *
     * @param idTransaction id transaksi yang sudah commit
     * @return {@code true} jika pemulihan berhasil; {@code false} jika gagal
     */
    public boolean recoverToTransaction(int idTransaction) {
        try {
            rollbackManager.recoverToTransaction(idTransaction);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            txStatuses.clear();
        }
    }

    /**
     * Memulihkan basis data ke keadaan tepat setelah record COMMIT transaksi tertentu.
     *
     * @param tx id transaksi yang sudah commit, tidak boleh null
     * @return {@code true} jika pemulihan berhasil; {@code false} jika gagal
     * @throws NullPointerException jika {@code tx} null
     * @throws IllegalArgumentException jika nilai {@code tx} melebihi batas 32-bit int
     */
    public boolean recoverToTransaction(TxId tx) {
        return recoverToTransaction(toTxIdInt(tx));
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
        } finally {
            txStatuses.clear();
        }
    }

    @Override
    public boolean redo() {
        return crashRecoveryManager != null && crashRecoveryManager.redo();
    }

    @Override
    public boolean undo() {
        if (crashRecoveryManager == null) {
            return false;
        }
        try {
            return crashRecoveryManager.undo();
        } finally {
            txStatuses.clear();
        }
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

    /**
     * Mengembalikan informasi batas atas transaksi dan timestamp logis dari WAL saat ini untuk
     * keperluan inisialisasi CCM pasca-recovery ({@code initializeAfterRecovery}).
     *
     * <p>Menggunakan batas atas ID transaksi sebagai {@code maxTimestamp} karena timestamp CCM
     * bersifat logis (monotonik per transaksi), bukan wall-clock epoch milidetik.
     *
     * @return {@link RecoveryInfo} berisi {@code maxTransactionId} dan {@code maxTimestamp}
     */
    public RecoveryInfo getRecoveryInfo() {
        long maxTxId = 0L;
        for (WALRecord rec : logger.getLogsSorted()) {
            maxTxId = Math.max(maxTxId, rec.getIdTransaction());
        }
        return new RecoveryInfo(maxTxId, maxTxId);
    }

    @Override
    public boolean beginTransaction(TxId tx) {
        return beginTransaction(toTxIdInt(tx));
    }

    @Override
    public boolean commitTransaction(TxId tx) {
        return commitTransaction(toTxIdInt(tx));
    }

    @Override
    public boolean rollbackTransaction(TxId tx) {
        return rollbackTransaction(toTxIdInt(tx));
    }

    @Override
    public TxStatus getTransactionStatus(TxId tx) {
        return getTransactionStatus(toTxIdInt(tx));
    }

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
    public void forceThrough(long lsn) throws RecoveryInternalException {
        try {
            logger.forceThrough(lsn);
        } catch (Exception e) {
            throw new RecoveryInternalException("Failed to force log through LSN " + lsn, e);
        }
    }

    @Override
    public void close() {
        try {
            logger.close();
        } catch (Exception ignored) {
        }
    }
}
