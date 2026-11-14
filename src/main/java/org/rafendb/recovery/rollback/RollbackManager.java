package org.rafendb.recovery.rollback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.LoggerErrorException;
import org.rafendb.recovery.log.WALRecord;

/**
 * FR3: membatalkan transaksi (FR-04) dan memulihkan basis data ke waktu atau transaksi tertentu
 * (FR-05). Dipakai oleh {@code FRManager} sebagai {@code rollbackManager}.
 *
 * <p><b>Record kompensasi.</b> Setiap undo terlebih dahulu menulis record kompensasi, yaitu
 * perubahan kebalikannya ({@link LogType#inverse()}, nilai sebelum dan sesudah ditukar), baru
 * kemudian menerapkan nilai lama lewat {@link UndoStorage} dengan LSN record kompensasi tersebut.
 * Jadi log selalu ditulis lebih dulu daripada data (WAL). Akibatnya:
 *
 * <ul>
 *   <li>Rollback yang terputus karena crash tetap benar: transaksi tanpa ABORT dibatalkan oleh
 *       crash recovery (FR5) dari record terbaru, termasuk record kompensasinya.
 *   <li>Hasil pemulihan historis dicatat sebagai satu transaksi sistem yang commit, sehingga redo
 *       setelah restart memutarnya ulang dan hasilnya tetap ada.
 * </ul>
 *
 * <p><b>Thread-safety.</b> Thread-safe. Rollback untuk transaksi berbeda dapat berjalan paralel,
 * rollback untuk transaksi yang sama dijalankan satu per satu. Pemulihan historis menunggu rollback
 * yang sedang berjalan selesai dan menahan rollback baru sampai pemulihan selesai.
 */
public final class RollbackManager {
    private final RollbackLog logger;
    private final UndoStorage storage;
    private final LongSupplier clock;

    /** Dipegang bersama (shared) oleh rollback, dan secara eksklusif oleh pemulihan historis. */
    private final ReentrantReadWriteLock restoreLock = new ReentrantReadWriteLock();

    /** Satu monitor per transaksi, agar satu transaksi tidak di-rollback dua kali bersamaan. */
    private final Map<Integer, Object> txLocks = new ConcurrentHashMap<>();

    /** Langkah undo yang tersisa untuk transaksi yang rollback-nya gagal di tengah jalan. */
    private final Map<Integer, Deque<Step>> pending = new ConcurrentHashMap<>();

    /**
     * Membuat manager yang memakai jam sistem untuk timestamp record kompensasi.
     *
     * @param logger logger WAL milik FR2, tidak boleh null
     * @param storage adapter Storage Manager, tidak boleh null
     * @throws NullPointerException jika ada argumen yang null
     */
    public RollbackManager(RollbackLog logger, UndoStorage storage) {
        this(logger, storage, System::currentTimeMillis);
    }

    /**
     * Membuat manager dengan sumber waktu tertentu, misalnya jam palsu untuk pengujian.
     *
     * @param logger logger WAL milik FR2, tidak boleh null
     * @param storage adapter Storage Manager, tidak boleh null
     * @param clock mengembalikan waktu sekarang dalam milidetik epoch, tidak boleh null
     * @throws NullPointerException jika ada argumen yang null
     */
    public RollbackManager(RollbackLog logger, UndoStorage storage, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------------------------------
    // FR-04: rollback transaksi
    // ------------------------------------------------------------------------------------------

    /**
     * Membatalkan semua perubahan sebuah transaksi dari yang terbaru, lalu menulis ABORT dan
     * mem-flush log. Dipanggil lewat {@code FRManager} ketika CCM membatalkan transaksi (ABORT
     * pengguna, deadlock, atau pelanggaran protokol konkurensi).
     *
     * <p>Method ini baru kembali normal setelah seluruh undo berhasil dan ABORT tersimpan
     * permanen. Jika melempar exception, transaksi belum selesai dibatalkan: CCM tetap memegang
     * lock-nya lalu memanggil method ini lagi, yang melanjutkan dari perubahan pertama yang belum
     * dibatalkan tanpa menulis record kompensasi ganda.
     *
     * <p>Prasyarat: transaksi tidak menulis perubahan baru setelah rollback-nya dimulai.
     *
     * @param idTransaction transaksi yang akan di-rollback
     * @return status rollback; lihat {@link RollbackStatus}
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal menerapkan undo
     */
    public RollbackStatus rollback(int idTransaction) {
        restoreLock.readLock().lock();
        try {
            synchronized (txLocks.computeIfAbsent(idTransaction, k -> new Object())) {
                return rollbackLocked(idTransaction);
            }
        } finally {
            restoreLock.readLock().unlock();
        }
    }

    /**
     * Versi boolean dari {@link #rollback(int)} untuk {@code FRManager}.
     *
     * @param idTransaction transaksi yang akan di-rollback
     * @return {@code true} jika transaksi kini sudah ter-abort (barusan atau sebelumnya); {@code
     *     false} jika sudah commit atau tidak dikenal
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal menerapkan undo
     */
    public boolean rollbackTransaction(int idTransaction) {
        return rollback(idTransaction).isAborted();
    }

    /** Me-rollback satu transaksi; pemanggil sudah memegang monitor transaksinya. */
    private RollbackStatus rollbackLocked(int idTransaction) {
        Deque<Step> todo = pending.get(idTransaction);
        if (todo == null) {
            List<WALRecord> records = logger.getLogsByTransaction(idTransaction);
            if (records.isEmpty()) {
                return RollbackStatus.UNKNOWN_TRANSACTION;
            }
            if (has(records, LogType.COMMIT)) {
                return RollbackStatus.ALREADY_COMMITTED;
            }
            if (has(records, LogType.ABORT)) {
                logger.flushAll(); // pemanggilan sebelumnya mungkin gagal saat mem-flush ABORT
                return RollbackStatus.ALREADY_ABORTED;
            }
            todo = new ArrayDeque<>();
            for (WALRecord r : records) {
                if (r.getLogType().isDataChange()) {
                    todo.push(new Step(r)); // push menaruh perubahan terbaru di depan
                }
            }
            pending.put(idTransaction, todo);
        }
        while (!todo.isEmpty()) {
            undo(idTransaction, todo.peek());
            todo.pop();
        }
        logger.logTransactionEvent(idTransaction, LogType.ABORT);
        pending.remove(idTransaction);
        logger.flushAll();
        return RollbackStatus.ROLLED_BACK;
    }

    // ------------------------------------------------------------------------------------------
    // FR-05: pemulihan historis
    // ------------------------------------------------------------------------------------------

    /**
     * Memulihkan basis data ke keadaan pada {@code targetTimestamp}: hanya perubahan transaksi
     * yang commit paling lambat pada waktu itu yang dipertahankan. Perubahan transaksi yang commit
     * setelahnya dibatalkan, termasuk transaksi yang dimulai sebelum target tetapi commit sesudah
     * target. Pembatalan dicatat sebagai satu transaksi sistem baru yang commit dan di-flush.
     *
     * <p>Prasyarat (mode pemeliharaan): tidak ada transaksi yang berjalan dan tidak ada yang
     * dimulai sampai method ini selesai. Setelahnya, CCM wajib memberi nomor transaksi baru di atas
     * {@link RollbackLog#getHighestTransactionId()}.
     *
     * @param targetTimestamp titik waktu tujuan, dalam milidetik epoch
     * @return jumlah perubahan yang dibatalkan; {@code 0} jika basis data sudah berada di titik itu
     * @throws IllegalArgumentException jika target di luar riwayat log (sebelum record pertama
     *     atau di masa depan); tidak ada data yang diubah
     * @throws IllegalStateException jika masih ada transaksi yang belum commit atau abort, atau log
     *     berisi record perubahan yang rusak; tidak ada data yang diubah
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal; perubahan yang sudah dipulihkan dibatalkan lagi
     */
    public int recoverToPointInTime(long targetTimestamp) {
        restoreLock.writeLock().lock();
        try {
            List<WALRecord> all = logger.getLogsSorted();
            if (targetTimestamp > clock.getAsLong()) {
                throw new IllegalArgumentException(
                        "Target " + targetTimestamp + " is in the future");
            }
            if (all.isEmpty()) {
                return 0;
            }
            long firstTimestamp = all.getFirst().getTimestamp();
            if (targetTimestamp < firstTimestamp) {
                throw new IllegalArgumentException(
                        "Target "
                                + targetTimestamp
                                + " is before the start of the log ("
                                + firstTimestamp
                                + ")");
            }
            Set<Integer> kept = new HashSet<>();
            for (WALRecord r : all) {
                if (r.getLogType() == LogType.COMMIT && r.getTimestamp() <= targetTimestamp) {
                    kept.add(r.getIdTransaction());
                }
            }
            return restore(all, kept);
        } finally {
            restoreLock.writeLock().unlock();
        }
    }

    /**
     * Memulihkan basis data ke keadaan tepat setelah record COMMIT sebuah transaksi. Yang
     * dipertahankan adalah transaksi yang COMMIT-nya tercatat sampai LSN tersebut, bukan
     * transaksi dengan ID yang lebih kecil. Prasyarat dan efeknya sama dengan {@link
     * #recoverToPointInTime}.
     *
     * @param idTransaction transaksi yang sudah commit
     * @return jumlah perubahan yang dibatalkan
     * @throws IllegalArgumentException jika transaksi tidak dikenal atau belum commit; tidak ada
     *     data yang diubah
     * @throws IllegalStateException jika masih ada transaksi yang belum commit atau abort, atau log
     *     berisi record perubahan yang rusak; tidak ada data yang diubah
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal; perubahan yang sudah dipulihkan dibatalkan lagi
     */
    public int recoverToTransaction(int idTransaction) {
        restoreLock.writeLock().lock();
        try {
            List<WALRecord> all = logger.getLogsSorted();
            long targetLsn = -1;
            for (WALRecord r : all) {
                if (r.getIdTransaction() == idTransaction && r.getLogType() == LogType.COMMIT) {
                    targetLsn = r.getLSN();
                    break;
                }
            }
            if (targetLsn < 0) {
                throw new IllegalArgumentException("T" + idTransaction + " has not committed");
            }
            Set<Integer> kept = new HashSet<>();
            for (WALRecord r : all) {
                if (r.getLogType() == LogType.COMMIT && r.getLSN() <= targetLsn) {
                    kept.add(r.getIdTransaction());
                }
            }
            return restore(all, kept);
        } finally {
            restoreLock.writeLock().unlock();
        }
    }

    /**
     * Membatalkan, dari yang terbaru, semua perubahan transaksi yang commit tetapi tidak termasuk
     * {@code kept}. Transaksi yang sudah abort dilewati karena perubahannya sudah dibatalkan oleh
     * record kompensasinya sendiri.
     *
     * <p>Dengan strict 2PL, transaksi di {@code kept} tidak pernah menimpa record yang lebih dulu
     * diubah transaksi yang commit sesudah target, jadi membatalkan sisanya dari belakang
     * menghasilkan keadaan pada target.
     */
    private int restore(List<WALRecord> all, Set<Integer> kept) {
        Set<Integer> finished = new HashSet<>();
        Set<Integer> aborted = new HashSet<>();
        Set<Integer> unfinished = new TreeSet<>();
        for (WALRecord r : all) {
            unfinished.add(r.getIdTransaction());
            if (r.getLogType() == LogType.COMMIT) {
                finished.add(r.getIdTransaction());
            } else if (r.getLogType() == LogType.ABORT) {
                finished.add(r.getIdTransaction());
                aborted.add(r.getIdTransaction());
            }
        }
        unfinished.removeAll(finished);
        unfinished.remove(0); // record checkpoint tidak terikat ke transaksi mana pun
        if (!unfinished.isEmpty()) {
            throw new IllegalStateException("Transactions still running: " + unfinished);
        }

        List<WALRecord> toUndo = new ArrayList<>();
        for (WALRecord r : all.reversed()) {
            int tx = r.getIdTransaction();
            if (r.getLogType().isDataChange() && !kept.contains(tx) && !aborted.contains(tx)) {
                if (r.getTableName() == null || r.getRid() == null) {
                    throw new IllegalStateException("Malformed data change record: " + r);
                }
                toUndo.add(r);
            }
        }
        if (toUndo.isEmpty()) {
            return 0;
        }

        int systemTx = logger.getHighestTransactionId() + 1;
        logger.logTransactionEvent(systemTx, LogType.BEGIN);
        try {
            for (WALRecord r : toUndo) {
                undo(systemTx, new Step(r));
            }
        } catch (RuntimeException e) {
            abortSystemTransaction(systemTx, e);
            throw e;
        }
        logger.logTransactionEvent(systemTx, LogType.COMMIT);
        logger.flushAll();
        return toUndo.size();
    }

    /**
     * Membatalkan transaksi sistem pemulihan yang gagal di tengah jalan, agar basis data kembali ke
     * keadaan sebelum pemulihan dimulai. Kegagalan di sini ditempelkan ke {@code cause}; transaksi
     * sistem lalu tersisa tanpa ABORT dan akan dibatalkan oleh crash recovery.
     */
    private void abortSystemTransaction(int systemTx, RuntimeException cause) {
        try {
            Deque<Step> todo = new ArrayDeque<>();
            for (WALRecord r : logger.getLogsByTransaction(systemTx)) {
                if (r.getLogType().isDataChange()) {
                    todo.push(new Step(r));
                }
            }
            // Perubahan yang LSN kompensasinya sudah ditulis tetapi belum diterapkan juga ikut
            // dibatalkan; menerapkan nilai lama dua kali aman karena applyUndo idempoten.
            pending.put(systemTx, todo);
            rollbackLocked(systemTx);
        } catch (RuntimeException inner) {
            cause.addSuppressed(inner);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Bantuan
    // ------------------------------------------------------------------------------------------

    /**
     * Menulis record kompensasi untuk satu perubahan (cukup sekali walaupun dicoba ulang), lalu
     * mengembalikan nilai lamanya lewat Storage Manager.
     */
    private void undo(int idTransaction, Step step) {
        WALRecord r = step.original;
        if (step.compensationLsn < 0) {
            step.compensationLsn =
                    logger.logDataChange(
                            idTransaction,
                            r.getLogType().inverse(),
                            clock.getAsLong(),
                            r.getTableName(),
                            r.getTableGeneration(),
                            r.getRid(),
                            r.getAfterValue(),
                            r.getBeforeValue());
        }
        storage.applyUndo(
                r.getTableName(),
                r.getTableGeneration(),
                r.getRid(),
                r.getBeforeValue(),
                step.compensationLsn);
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

    /** Satu perubahan yang akan dibatalkan, beserta LSN record kompensasinya setelah ditulis. */
    private static final class Step {
        final WALRecord original;
        long compensationLsn = -1;

        Step(WALRecord original) {
            this.original = original;
        }
    }
}
