package org.rafendb.recovery.rollback;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.WALRecord;

/**
 * Mock Rollback Manager untuk Milestone 1 (FR-04, FR-05) sesuai diagram kelas FRM. Dipakai oleh
 * {@code FRManager} sebagai {@code rollbackManager}.
 *
 * <p>Implementasi sederhana: membaca log lalu langsung mengembalikan nilai sebelum perubahan lewat
 * {@link UndoStorage}. Mock ini <b>belum</b> menulis record kompensasi, belum bisa melanjutkan
 * rollback yang gagal di tengah jalan, dan belum aman dari crash saat rollback. Hal-hal tersebut
 * dikerjakan pada milestone berikutnya.
 *
 * <p>Thread-safe: semua method publik {@code synchronized}.
 */
public final class RollbackManager {
    private final RollbackLog logger;
    private final UndoStorage storage;

    /**
     * Membuat mock RollbackManager.
     *
     * @param logger logger WAL milik FR2, tidak boleh null
     * @param storage adapter Storage Manager, tidak boleh null
     * @throws NullPointerException jika ada argumen yang null
     */
    public RollbackManager(RollbackLog logger, UndoStorage storage) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /**
     * Mock rollback: mengembalikan nilai lama semua perubahan transaksi dari yang terbaru, lalu
     * menulis ABORT dan mem-flush log.
     *
     * @param idTransaction transaksi yang akan di-rollback
     * @return {@code true} jika transaksi kini sudah ter-abort (barusan atau sebelumnya); {@code
     *     false} jika sudah commit atau tidak punya record log
     */
    public synchronized boolean rollbackTransaction(int idTransaction) {
        List<WALRecord> records = logger.getLogsByTransaction(idTransaction);
        if (records.isEmpty() || has(records, LogType.COMMIT)) {
            return false;
        }
        if (has(records, LogType.ABORT)) {
            return true;
        }
        for (int i = records.size() - 1; i >= 0; i--) {
            undo(records.get(i));
        }
        logger.logTransactionEvent(idTransaction, LogType.ABORT);
        logger.flushAll();
        return true;
    }

    /**
     * Mock pemulihan ke titik waktu: membatalkan semua perubahan yang ditulis setelah {@code
     * targetTimestamp}, serta perubahan dari transaksi yang belum commit pada waktu itu.
     *
     * @param targetTimestamp titik waktu tujuan, dalam milidetik epoch
     * @return jumlah perubahan yang dibatalkan
     */
    public synchronized int recoverToPointInTime(long targetTimestamp) {
        List<WALRecord> all = logger.getLogsSorted();
        int cut = all.size();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getTimestamp() > targetTimestamp) {
                cut = i;
                break;
            }
        }
        return restore(all, cut);
    }

    /**
     * Mock pemulihan ke kondisi tepat setelah sebuah transaksi commit.
     *
     * @param idTransaction transaksi yang sudah commit
     * @return jumlah perubahan yang dibatalkan
     * @throws IllegalArgumentException jika transaksi belum commit
     */
    public synchronized int recoverToTransaction(int idTransaction) {
        List<WALRecord> all = logger.getLogsSorted();
        for (int i = 0; i < all.size(); i++) {
            WALRecord r = all.get(i);
            if (r.getIdTransaction() == idTransaction && r.getLogType() == LogType.COMMIT) {
                return restore(all, i + 1);
            }
        }
        throw new IllegalArgumentException("T" + idTransaction + " has not committed");
    }

    /**
     * Membatalkan (dari yang terbaru) perubahan setelah posisi {@code cut}, serta perubahan dari
     * transaksi yang belum commit sebelum posisi itu.
     */
    private int restore(List<WALRecord> all, int cut) {
        Set<Integer> committedBeforeCut = new HashSet<>();
        for (int i = 0; i < cut; i++) {
            if (all.get(i).getLogType() == LogType.COMMIT) {
                committedBeforeCut.add(all.get(i).getIdTransaction());
            }
        }
        int count = 0;
        for (int i = all.size() - 1; i >= 0; i--) {
            WALRecord r = all.get(i);
            boolean keep = i < cut && committedBeforeCut.contains(r.getIdTransaction());
            if (!keep && undo(r)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Mengembalikan nilai sebelum perubahan jika record berupa perubahan data.
     *
     * @return {@code true} jika ada data yang dikembalikan
     */
    private boolean undo(WALRecord r) {
        if (!r.getLogType().isDataChange()) {
            return false;
        }
        storage.applyUndo(r.getTableName(), r.getIdRecord(), r.getBeforeValue(), r.getLSN());
        return true;
    }

    /** Mengecek apakah ada record bertipe {@code type} di dalam daftar. */
    private static boolean has(List<WALRecord> records, LogType type) {
        for (WALRecord r : records) {
            if (r.getLogType() == type) {
                return true;
            }
        }
        return false;
    }
}
