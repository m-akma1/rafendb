package org.rafendb.recovery.restart;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.tuple.Rid;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
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
 */
public class CrashRecoveryEngine {

    private final Logger logger;
    private final UndoStorage storage;
    private final LongSupplier clock;
    private boolean crashRecovered = false;
    private boolean redoExecuted = false;
    private boolean undoExecuted = false;

    private final Set<Integer> committedTransactions = new HashSet<>();
    private final Set<Integer> abortedTransactions = new HashSet<>();
    private final Set<Integer> loserTransactions = new LinkedHashSet<>();
    private int highestTransactionId = 0;
    private int redoCount = 0;
    private int undoCount = 0;

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
        this.logger = logger;
        this.storage = storage;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Membuat instance CrashRecoveryEngine langsung dengan StorageManager. Alamat baris diteruskan
     * apa adanya sebagai {@link Rid}.
     *
     * @param logger logger WAL
     * @param storageManager interface manajer storage
     */
    public CrashRecoveryEngine(Logger logger, StorageManager storageManager) {
        this(
                logger,
                storageManager != null
                        ? org.rafendb.recovery.FRManager.storageAdapter(storageManager)
                        : null);
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
     * diterapkan ulang sesuai urutan LSN, apa pun status transaksinya. Penerapan bersifat idempoten
     * sehingga aman diulang.
     *
     * @return true jika fase Redo berhasil
     */
    public synchronized boolean redo() {
        this.redoExecuted = true;
        redoCount = 0;
        if (logger == null || storage == null) {
            return true;
        }
        try {
            List<WALRecord> allLogs = readLogs();
            performAnalysisPass(allLogs);
            for (WALRecord rec : allLogs) {
                if (rec.getLogType().isDataChange()) {
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
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Menulis record kompensasi untuk {@code rec}, lalu menerapkan before-image-nya. */
    private void undoOne(WALRecord rec) {
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
