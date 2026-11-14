package org.rafendb.recovery.restart;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.rollback.UndoStorage;

/**
 * Crash Recovery Engine berbasis protokol ARIES (FR-07) sesuai diagram kelas Failure Recovery Manager (FRM).
 *
 * <p>Menjalankan pemulihan basis data saat startup setelah crash atau penghentian abnormal:
 * <ol>
 *   <li><b>Analysis Pass:</b> Memindai WAL untuk mengidentifikasi transaksi aktif, transaksi committed,
 *       transaksi yang sudah abort, dan transaksi tidak lengkap (loser transactions).</li>
 *   <li><b>Redo Pass:</b> Mengulangi (redo) perubahan data milik transaksi yang telah commit untuk
 *       menjamin durabilitas (ACID Durability).</li>
 *   <li><b>Undo Pass:</b> Membatalkan (undo) perubahan data dari transaksi yang belum commit (loser transactions)
 *       secara terbalik (reverse LSN order), mencatat record ABORT, dan mem-flush log ke disk (ACID Atomicity).</li>
 * </ol>
 */
public class CrashRecoveryEngine {

    private final Logger logger;
    private final UndoStorage storage;
    private boolean crashRecovered = false;
    private boolean redoExecuted = false;
    private boolean undoExecuted = false;

    private final Set<Integer> committedTransactions = new HashSet<>();
    private final Set<Integer> loserTransactions = new LinkedHashSet<>();
    private int highestTransactionId = 0;

    /**
     * Hasil analisis fasa Analysis pada pemulihan crash ARIES.
     *
     * @param committedTransactions kumpulan ID transaksi yang berhasil commit
     * @param loserTransactions kumpulan ID transaksi yang belum selesai (harus di-undo)
     * @param highestTransactionId id transaksi tertinggi yang tercatat di log
     */
    public record AnalysisResult(
            Set<Integer> committedTransactions,
            Set<Integer> loserTransactions,
            int highestTransactionId) {}

    /**
     * Konstruktor default CrashRecoveryEngine tanpa dependensi (mode in-memory mandiri).
     */
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
        this.logger = logger;
        this.storage = storage;
    }

    /**
     * Membuat instance CrashRecoveryEngine langsung dengan StorageManager.
     *
     * @param logger logger WAL
     * @param storageManager interface manajer storage
     */
    public CrashRecoveryEngine(
            Logger logger, org.rafendb.api.storage.StorageManager storageManager) {
        this(
                logger,
                storageManager != null
                        ? (tableName, idRecord, image, lsn) -> {
                            try {
                                org.rafendb.common.tuple.Rid rid =
                                        idRecord >= 0
                                                ? new org.rafendb.common.tuple.Rid(
                                                        (long) idRecord / 1000, idRecord % 1000)
                                                : null;
                                Optional<Row> row =
                                        image == null
                                                ? Optional.empty()
                                                : Optional.of(
                                                        RowImageCodec.DEFAULT.decode(
                                                                image.getBytes(
                                                                        StandardCharsets.UTF_8)));

                                storageManager.applyRecovery(tableName, rid, row, lsn);
                            } catch (Exception e) {
                                throw new RuntimeException(
                                        "StorageManager applyRecovery failed", e);
                            }
                        }
                        : null);
    }

    /**
     * Menjalankan analisis log ARIES (Analysis Pass) dan mengembalikan hasilnya.
     *
     * @return {@link AnalysisResult} berisi transaksi committed, loser, dan id transaksi tertinggi
     */
    public synchronized AnalysisResult analyze() {
        List<WALRecord> allLogs = logger != null ? logger.getLogsSorted() : List.of();
        performAnalysisPass(allLogs);
        return new AnalysisResult(
                getCommittedTransactions(), getLoserTransactions(), getHighestTransactionId());
    }

    /**
     * Menjalankan analisis log (Analysis Pass) ARIES untuk mengklasifikasi status transaksi.
     */
    private synchronized void performAnalysisPass(List<WALRecord> allLogs) {
        committedTransactions.clear();
        loserTransactions.clear();
        highestTransactionId = 0;

        Set<Integer> allTxs = new LinkedHashSet<>();
        Set<Integer> abortedTxs = new HashSet<>();

        for (WALRecord rec : allLogs) {
            int txId = rec.getIdTransaction();
            if (txId > highestTransactionId) {
                highestTransactionId = txId;
            }
            if (txId > 0) {
                allTxs.add(txId);
            }
            if (rec.getLogType() == LogType.COMMIT) {
                committedTransactions.add(txId);
            } else if (rec.getLogType() == LogType.ABORT) {
                abortedTxs.add(txId);
            }
        }

        // Loser transactions: transaksi yang dimulai namun belum commit dan belum abort saat crash
        loserTransactions.addAll(allTxs);
        loserTransactions.removeAll(committedTransactions);
        loserTransactions.removeAll(abortedTxs);
    }

    /**
     * Eksekusi prosedur pemulihan kegagalan sistem (Crash Recovery).
     *
     * <p>Melakukan fase Analysis, Redo, lalu Undo untuk mengembalikan keadaan basis data ke konsistensi penuh.
     *
     * @return true jika seluruh tahapan pemulihan crash berhasil
     */
    public synchronized boolean recoverFromCrash() {
        try {
            boolean r = redo();
            boolean u = undo();
            this.crashRecovered = r && u;
            return this.crashRecovered;
        } catch (Exception e) {
            this.crashRecovered = false;
            return false;
        }
    }

    /**
     * Eksekusi fase Redo.
     *
     * <p>Mengulangi (redo) perubahan dari transaksi yang telah committed dalam urutan LSN
     * untuk memastikan persistensi data.
     *
     * @return true jika fase Redo berhasil
     */
    public synchronized boolean redo() {
        this.redoExecuted = true;
        if (logger == null || storage == null) {
            return true;
        }

        try {
            List<WALRecord> allLogs = logger.getLogsSorted();
            performAnalysisPass(allLogs);

            for (WALRecord rec : allLogs) {
                if (rec.getLogType().isDataChange()
                        && committedTransactions.contains(rec.getIdTransaction())) {
                    storage.applyUndo(
                            rec.getTableName(), rec.getIdRecord(), rec.getAfterVal(), rec.getLSN());
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Eksekusi fase Undo.
     *
     * <p>Membatalkan (undo) perubahan dari transaksi yang belum committed (loser transactions)
     * dalam urutan terbalik dari LSN terbaru, menuliskan record ABORT, dan mem-flush WAL.
     *
     * @return true jika fase Undo berhasil
     */
    public synchronized boolean undo() {
        this.undoExecuted = true;
        if (logger == null || storage == null) {
            return true;
        }

        try {
            List<WALRecord> allLogs = logger.getLogsSorted();
            performAnalysisPass(allLogs);

            // Batalkan data change transaksi loser secara terbalik (reverse LSN order)
            for (int i = allLogs.size() - 1; i >= 0; i--) {
                WALRecord rec = allLogs.get(i);
                if (rec.getLogType().isDataChange()
                        && loserTransactions.contains(rec.getIdTransaction())) {
                    storage.applyUndo(
                            rec.getTableName(),
                            rec.getIdRecord(),
                            rec.getBeforeVal(),
                            rec.getLSN());
                }
            }

            // Catat ABORT untuk setiap transaksi loser yang telah berhasil dibatalkan dan flush ke
            // disk
            for (int loserTx : loserTransactions) {
                logger.logTransactionEvent(loserTx, LogType.ABORT);
            }
            logger.flushAll();

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return true jika recoverFromCrash telah berhasil dieksekusi
     */
    public boolean isCrashRecovered() {
        return crashRecovered;
    }

    /**
     * @return true jika redo telah dieksekusi
     */
    public boolean isRedoExecuted() {
        return redoExecuted;
    }

    /**
     * @return true jika undo telah dieksekusi
     */
    public boolean isUndoExecuted() {
        return undoExecuted;
    }

    /**
     * @return kumpulan ID transaksi committed yang ditemukan saat recovery
     */
    public Set<Integer> getCommittedTransactions() {
        return Collections.unmodifiableSet(committedTransactions);
    }

    /**
     * @return kumpulan ID transaksi tidak lengkap (loser transactions) yang dibatalkan
     */
    public Set<Integer> getLoserTransactions() {
        return Collections.unmodifiableSet(loserTransactions);
    }

    /**
     * @return nomor transaksi tertinggi yang tercatat pada log WAL
     */
    public int getHighestTransactionId() {
        return highestTransactionId;
    }
}
