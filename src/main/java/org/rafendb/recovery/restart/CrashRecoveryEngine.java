package org.rafendb.recovery.restart;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import org.rafendb.common.tuple.Rid;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.LoggerErrorException;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.rollback.UndoStorage;

/**
 * Crash Recovery Engine (FR-07) yang terinspirasi ARIES, sesuai diagram kelas Failure Recovery
 * Manager (FRM).
 *
 * <p>Menjalankan pemulihan basis data saat startup setelah crash atau penghentian abnormal:
 *
 * <ol>
 *   <li><b>Analysis:</b> memindai WAL untuk memisahkan transaksi committed, transaksi yang sudah
 *       abort, dan transaksi <i>loser</i> (belum punya COMMIT maupun ABORT saat crash).
 *   <li><b>Redo (repeating history):</b> menerapkan ulang after-image <i>setiap</i> perubahan data
 *       sesuai urutan LSN, termasuk milik transaksi yang di-abort dan record kompensasinya, sehingga
 *       kondisi data sama persis dengan isi log saat crash.
 *   <li><b>Undo:</b> membatalkan perubahan transaksi loser dari LSN terbaru ke terlama. Setiap
 *       pembatalan dicatat dulu sebagai record kompensasi (CLR) sebelum diterapkan ke storage, lalu
 *       ABORT ditulis untuk setiap loser dan log di-flush.
 * </ol>
 *
 * <p>Record kompensasi berupa perubahan data biasa bertipe kebalikan ({@link LogType#inverse()})
 * dengan before/after image yang ditukar, sama seperti yang ditulis {@code RollbackManager} (FR3).
 * Karena undo membatalkan semua perubahan data loser, termasuk kompensasi yang sempat ditulis
 * sebelum crash, hasil akhirnya tetap benar walaupun crash terjadi lagi di tengah recovery.
 *
 * <p>Semua penerapan ke storage memakai {@link Rid} penuh dan generasi tabel dari {@link
 * WALRecord}, bukan id record {@code int} yang sempit. Thread-safe: semua method publik
 * ter-synchronize.
 *
 * <p>Redo bisa dimulai dari checkpoint terakhir jika sumber LSN checkpoint diberikan (lihat {@link
 * #CrashRecoveryEngine(Logger, UndoStorage, LongSupplier, LongSupplier)}). Analysis tetap memindai
 * seluruh log, dan redo kembali ke awal log jika checkpoint tersebut tidak terbukti aman.
 *
 * <p>Kelas ini hanya bergantung pada {@link UndoStorage}, tidak pada {@code StorageManager} maupun
 * facade FRM, sehingga tidak ada dependensi siklik dengan {@code FRManager}. Adapter ke {@code
 * StorageManager} dibuat oleh pemanggil.
 */
public class CrashRecoveryEngine {

    private final Logger logger;
    private final UndoStorage storage;
    private final LongSupplier clock;
    private final LongSupplier checkpointLsn;
    private boolean crashRecovered = false;
    private boolean redoExecuted = false;
    private boolean undoExecuted = false;

    private final Set<Integer> committedTransactions = new HashSet<>();
    private final Set<Integer> abortedTransactions = new HashSet<>();
    private final Set<Integer> loserTransactions = new LinkedHashSet<>();
    private int highestTransactionId = 0;
    private int redoCount = 0;
    private int undoCount = 0;
    private long redoStartLsn = 0;

    /**
     * Hasil analisis fasa Analysis pada pemulihan crash.
     *
     * @param committedTransactions kumpulan ID transaksi yang berhasil commit
     * @param loserTransactions kumpulan ID transaksi yang belum selesai (harus di-undo)
     * @param highestTransactionId id transaksi tertinggi yang tercatat di log
     */
    public record AnalysisResult(
            Set<Integer> committedTransactions,
            Set<Integer> loserTransactions,
            int highestTransactionId) {}

    /** Konstruktor default CrashRecoveryEngine tanpa dependensi (mode in-memory mandiri). */
    public CrashRecoveryEngine() {
        this(null, (UndoStorage) null);
    }

    /**
     * Membuat instance CrashRecoveryEngine dengan logger WAL dan storage adapter.
     *
     * @param logger logger WAL
     * @param storage storage adapter untuk menerapkan perubahan data pada buffer/storage
     */
    public CrashRecoveryEngine(Logger logger, UndoStorage storage) {
        this(logger, storage, System::currentTimeMillis);
    }

    /**
     * Membuat instance CrashRecoveryEngine dengan sumber waktu sendiri untuk timestamp record
     * kompensasi. Dipakai terutama untuk pengujian.
     *
     * @param logger logger WAL
     * @param storage storage adapter untuk menerapkan perubahan data pada buffer/storage
     * @param clock sumber waktu (milidetik epoch), tidak boleh null
     */
    public CrashRecoveryEngine(Logger logger, UndoStorage storage, LongSupplier clock) {
        this(logger, storage, clock, () -> 0L);
    }

    /**
     * Membuat instance CrashRecoveryEngine yang memulai redo dari checkpoint terakhir, misalnya
     * dengan {@code checkpointManager::getLastCheckpointLsn}.
     *
     * <p>Checkpoint hanya dipakai jika LSN-nya adalah record {@code CHECKPOINT_END} di log ini,
     * didahului {@code CHECKPOINT_BEGIN}, dan tidak ada transaksi yang masih aktif saat {@code
     * CHECKPOINT_BEGIN}. Selain itu (misalnya pointer basi atau milik log lain), redo mulai dari
     * awal log. Pemanggil wajib memastikan checkpoint tersebut mem-flush storage; checkpoint tanpa
     * {@code StorageManager} tidak boleh dipakai di sini.
     *
     * @param logger logger WAL
     * @param storage storage adapter untuk menerapkan perubahan data pada buffer/storage
     * @param clock sumber waktu (milidetik epoch), tidak boleh null
     * @param checkpointLsn sumber LSN checkpoint lengkap terakhir, {@code 0} jika belum ada; tidak
     *     boleh null
     */
    public CrashRecoveryEngine(
            Logger logger, UndoStorage storage, LongSupplier clock, LongSupplier checkpointLsn) {
        this.logger = logger;
        this.storage = storage;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.checkpointLsn =
                Objects.requireNonNull(checkpointLsn, "checkpointLsn must not be null");
    }

    /**
     * Menjalankan fase Analysis dan mengembalikan hasilnya.
     *
     * @return {@link AnalysisResult} berisi transaksi committed, loser, dan id transaksi tertinggi
     */
    public synchronized AnalysisResult analyze() {
        performAnalysisPass(readLogs());
        return new AnalysisResult(
                getCommittedTransactions(), getLoserTransactions(), getHighestTransactionId());
    }

    /** Mengklasifikasi status setiap transaksi berdasarkan isi log. */
    private void performAnalysisPass(List<WALRecord> allLogs) {
        committedTransactions.clear();
        abortedTransactions.clear();
        loserTransactions.clear();
        highestTransactionId = 0;

        Set<Integer> allTxs = new LinkedHashSet<>();
        for (WALRecord rec : allLogs) {
            int txId = rec.getIdTransaction();
            highestTransactionId = Math.max(highestTransactionId, txId);
            if (txId > 0) {
                allTxs.add(txId);
            }
            if (rec.getLogType() == LogType.COMMIT) {
                committedTransactions.add(txId);
            } else if (rec.getLogType() == LogType.ABORT) {
                abortedTransactions.add(txId);
            }
        }

        loserTransactions.addAll(allTxs);
        loserTransactions.removeAll(committedTransactions);
        loserTransactions.removeAll(abortedTransactions);
    }

    /**
     * Eksekusi prosedur pemulihan kegagalan sistem: Analysis, Redo, lalu Undo.
     *
     * <p>Method ini sengaja tidak memanggil {@code StorageManager.flush()}. Saat kembali dengan
     * {@code true}, semua record kompensasi dan ABORT sudah di-flush ke WAL, jadi hasil recovery
     * sudah durable: jika crash lagi, redo akan mengulang sejarah dan undo tidak membatalkan apa
     * pun. Page kotor milik storage boleh tetap di buffer karena eviction dijaga oleh {@code
     * WalSync.forceThrough}. Pemanggil saat startup sebaiknya membuat checkpoint (yang mem-flush
     * storage) setelah method ini berhasil dan sebelum transaksi baru diterima, agar recovery
     * berikutnya lebih singkat.
     *
     * @return true jika seluruh tahapan pemulihan crash berhasil
     */
    public synchronized boolean recoverFromCrash() {
        boolean r = redo();
        boolean u = r && undo();
        this.crashRecovered = r && u;
        return this.crashRecovered;
    }

    /**
     * Eksekusi fase Redo dengan mengulang sejarah: after-image setiap perubahan data di log
     * diterapkan ulang sesuai urutan LSN, apa pun status transaksinya. Jika ada checkpoint yang
     * aman, perubahan sebelum checkpoint dilewati karena sudah ada di storage. Penerapan bersifat
     * idempoten sehingga aman diulang.
     *
     * @return true jika fase Redo berhasil
     */
    public synchronized boolean redo() {
        this.redoExecuted = true;
        redoCount = 0;
        redoStartLsn = 0;
        if (logger == null || storage == null) {
            return true;
        }
        try {
            List<WALRecord> allLogs = readLogs();
            performAnalysisPass(allLogs);
            redoStartLsn = safeCheckpointLsn(allLogs);
            for (WALRecord rec : allLogs) {
                if (rec.getLogType().isDataChange() && rec.getLSN() > redoStartLsn) {
                    storage.applyUndo(
                            rec.getTableName(),
                            rec.getTableGeneration(),
                            rec.getRid(),
                            rec.getAfterValue(),
                            rec.getLSN());
                    redoCount++;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Eksekusi fase Undo: membatalkan perubahan data transaksi loser dari LSN terbaru ke terlama.
     * Setiap pembatalan dicatat dulu sebagai record kompensasi (dengan {@link Rid} dan generasi
     * tabel asal), baru diterapkan ke storage. Setelah semua dibatalkan, ABORT ditulis untuk setiap
     * loser dan log di-flush.
     *
     * @return true jika fase Undo berhasil
     */
    public synchronized boolean undo() {
        this.undoExecuted = true;
        undoCount = 0;
        if (logger == null || storage == null) {
            return true;
        }
        try {
            List<WALRecord> allLogs = readLogs();
            performAnalysisPass(allLogs);

            for (int i = allLogs.size() - 1; i >= 0; i--) {
                WALRecord rec = allLogs.get(i);
                if (rec.getLogType().isDataChange()
                        && loserTransactions.contains(rec.getIdTransaction())) {
                    undoOne(rec);
                }
            }

            for (int loserTx : loserTransactions) {
                logger.logTransactionEvent(loserTx, LogType.ABORT);
            }
            logger.flushAll();
            return true;
        } catch (LoggerErrorException | RuntimeException e) {
            return false;
        }
    }

    /** Menulis record kompensasi untuk {@code rec}, lalu menerapkan before-image-nya. */
    private void undoOne(WALRecord rec) throws LoggerErrorException {
        long compensationLsn =
                logger.logDataChange(
                        rec.getIdTransaction(),
                        rec.getLogType().inverse(),
                        clock.getAsLong(),
                        rec.getTableName(),
                        rec.getTableGeneration(),
                        rec.getRid(),
                        rec.getAfterValue(),
                        rec.getBeforeValue());
        storage.applyUndo(
                rec.getTableName(),
                rec.getTableGeneration(),
                rec.getRid(),
                rec.getBeforeValue(),
                compensationLsn);
        undoCount++;
    }

    /**
     * Mengembalikan LSN checkpoint jika redo aman dimulai dari sana, atau {@code 0} untuk redo
     * penuh. Aman berarti LSN tersebut adalah {@code CHECKPOINT_END} di log ini, ada {@code
     * CHECKPOINT_BEGIN} sebelumnya, dan tidak ada transaksi aktif saat {@code CHECKPOINT_BEGIN}.
     */
    private long safeCheckpointLsn(List<WALRecord> allLogs) {
        long target;
        try {
            target = checkpointLsn.getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
        if (target <= 0) {
            return 0;
        }
        int endIndex = -1;
        for (int i = 0; i < allLogs.size(); i++) {
            if (allLogs.get(i).getLSN() == target) {
                endIndex = i;
                break;
            }
        }
        if (endIndex < 0 || allLogs.get(endIndex).getLogType() != LogType.CHECKPOINT_END) {
            return 0;
        }
        int beginIndex = -1;
        for (int i = endIndex - 1; i >= 0; i--) {
            if (allLogs.get(i).getLogType() == LogType.CHECKPOINT_BEGIN) {
                beginIndex = i;
                break;
            }
        }
        if (beginIndex < 0) {
            return 0;
        }
        Set<Integer> active = new HashSet<>();
        for (int i = 0; i < beginIndex; i++) {
            WALRecord rec = allLogs.get(i);
            int txId = rec.getIdTransaction();
            if (txId <= 0) {
                continue;
            }
            if (rec.getLogType() == LogType.COMMIT || rec.getLogType() == LogType.ABORT) {
                active.remove(txId);
            } else {
                active.add(txId);
            }
        }
        return active.isEmpty() ? target : 0;
    }

    private List<WALRecord> readLogs() {
        return logger != null ? logger.getLogsSorted() : List.of();
    }

    /**
     * @return true jika recoverFromCrash telah berhasil dieksekusi
     */
    public synchronized boolean isCrashRecovered() {
        return crashRecovered;
    }

    /**
     * @return true jika redo telah dieksekusi
     */
    public synchronized boolean isRedoExecuted() {
        return redoExecuted;
    }

    /**
     * @return true jika undo telah dieksekusi
     */
    public synchronized boolean isUndoExecuted() {
        return undoExecuted;
    }

    /**
     * @return jumlah perubahan data yang diterapkan ulang pada redo terakhir
     */
    public synchronized int getRedoCount() {
        return redoCount;
    }

    /**
     * @return LSN checkpoint tempat redo terakhir dimulai, atau {@code 0} jika redo dari awal log
     */
    public synchronized long getRedoStartLsn() {
        return redoStartLsn;
    }

    /**
     * @return jumlah perubahan data loser yang dibatalkan pada undo terakhir
     */
    public synchronized int getUndoCount() {
        return undoCount;
    }

    /**
     * @return kumpulan ID transaksi committed yang ditemukan saat recovery
     */
    public synchronized Set<Integer> getCommittedTransactions() {
        return Collections.unmodifiableSet(new HashSet<>(committedTransactions));
    }

    /**
     * @return kumpulan ID transaksi tidak lengkap (loser transactions) yang dibatalkan
     */
    public synchronized Set<Integer> getLoserTransactions() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(loserTransactions));
    }

    /**
     * @return nomor transaksi tertinggi yang tercatat pada log WAL
     */
    public synchronized int getHighestTransactionId() {
        return highestTransactionId;
    }
}
