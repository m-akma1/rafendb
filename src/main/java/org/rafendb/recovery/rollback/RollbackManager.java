package org.rafendb.recovery.rollback;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.LoggerErrorException;
import org.rafendb.recovery.log.WALRecord;

/**
 * FR3: membatalkan transaksi (FR-04) dan memulihkan basis data ke titik sebelumnya (FR-05).
 * Dipakai oleh {@code FRManager} sebagai {@code rollbackManager}.
 *
 * <p>Setiap undo terlebih dahulu menulis record kompensasi (perubahan kebalikannya, lewat {@link
 * RollbackLog#logDataChange}), baru kemudian menerapkan isi lama lewat {@link UndoStorage}. Dengan
 * begitu log selalu ditulis lebih dulu daripada data (WAL). Record kompensasi berupa perubahan
 * data biasa, jadi redo setelah crash akan memutarnya ulang seperti perubahan lain.
 *
 * <p>Thread-safe. Rollback untuk transaksi yang berbeda dapat berjalan paralel, sedangkan rollback
 * untuk transaksi yang sama dijalankan satu per satu. Proses pemulihan menunggu rollback yang
 * sedang berjalan selesai dan menahan rollback baru.
 */
public final class RollbackManager {
    private final RollbackLog logger;
    private final UndoStorage storage;
    private final LongSupplier clock;

    /** Dipegang bersama (shared) oleh rollback, dan secara eksklusif oleh pemulihan. */
    private final ReentrantReadWriteLock restoreLock = new ReentrantReadWriteLock();

    /** Satu monitor per transaksi, agar satu transaksi tidak di-rollback dua kali bersamaan. */
    private final Map<Integer, Object> txLocks = new ConcurrentHashMap<>();

    /** Langkah undo yang tersisa untuk transaksi yang rollback-nya gagal di tengah jalan. */
    private final Map<Integer, Deque<Step>> pending = new ConcurrentHashMap<>();

    /**
     * Membuat manager yang memakai jam sistem untuk timestamp record kompensasi.
     *
     * @param logger logger milik FR2, tidak boleh null
     * @param storage adapter Storage Manager, tidak boleh null
     * @throws NullPointerException jika ada argumen yang null
     */
    public RollbackManager(RollbackLog logger, UndoStorage storage) {
        this(logger, storage, System::currentTimeMillis);
    }

    /**
     * Membuat manager dengan sumber waktu tertentu. Untuk keperluan test.
     *
     * @param logger logger milik FR2, tidak boleh null
     * @param storage adapter Storage Manager, tidak boleh null
     * @param clock mengembalikan waktu sekarang dalam milidetik epoch, tidak boleh null
     * @throws NullPointerException jika ada argumen yang null
     */
    RollbackManager(RollbackLog logger, UndoStorage storage, LongSupplier clock) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Membatalkan semua perubahan sebuah transaksi mulai dari yang terbaru, lalu menulis ABORT dan
     * mem-flush log. Dipanggil lewat {@code FRManager.rollbackTransaction} ketika CCM membatalkan
     * transaksi (ABORT dari pengguna, deadlock, atau pelanggaran timestamp).
     *
     * <p>Idempoten: memanggilnya lagi setelah mengembalikan {@code true} tidak mengubah apa pun.
     * Jika method ini melempar exception, transaksi tetap berstatus FAILED; CCM tetap memegang lock
     * dan memanggil method ini lagi, yang akan melanjutkan dari perubahan pertama yang belum
     * dibatalkan.
     *
     * <p>Prasyarat: transaksi tidak menulis apa pun lagi setelah rollback-nya dimulai.
     *
     * @param idTransaction transaksi yang akan di-rollback
     * @return {@code true} jika transaksi kini sudah ter-abort (barusan atau sebelumnya); {@code
     *     false} jika transaksi tidak bisa di-rollback karena sudah commit atau tidak punya record
     *     log
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal menerapkan undo
     */
    public boolean rollbackTransaction(int idTransaction) {
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
     * Memulihkan basis data ke kondisi pada {@code targetTimestamp}: semua perubahan yang ditulis
     * setelah waktu itu dibatalkan, begitu pula semua perubahan dari transaksi yang belum commit
     * pada waktu itu. Perubahan dibatalkan dari yang terbaru di dalam satu transaksi sistem baru,
     * yang di-commit dan di-flush di akhir.
     *
     * <p>Prasyarat: tidak ada transaksi yang sedang berjalan, dan tidak ada yang dimulai sampai
     * method ini selesai. Setelahnya, CCM wajib memberi nomor transaksi baru di atas {@link
     * RollbackLog#getHighestTransactionId()}.
     *
     * @param targetTimestamp titik waktu tujuan, dalam milidetik epoch
     * @return jumlah perubahan yang dibatalkan; {@code 0} jika basis data sudah berada di titik itu
     * @throws IllegalStateException jika masih ada transaksi yang belum commit atau abort
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal; perubahan yang sudah dibatalkan akan dikembalikan
     */
    public int recoverToPointInTime(long targetTimestamp) {
        restoreLock.writeLock().lock();
        try {
            List<WALRecord> all = logger.getLogsSorted();
            int cut = all.size();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).getTimestamp() > targetTimestamp) {
                    cut = i;
                    break;
                }
            }
            return restore(all, cut);
        } finally {
            restoreLock.writeLock().unlock();
        }
    }

    /**
     * Memulihkan basis data ke kondisi tepat setelah sebuah transaksi commit. Sama seperti {@link
     * #recoverToPointInTime}, dengan record COMMIT transaksi tersebut sebagai titik tujuan.
     *
     * @param idTransaction transaksi yang sudah commit
     * @return jumlah perubahan yang dibatalkan
     * @throws IllegalArgumentException jika transaksi belum commit
     * @throws IllegalStateException jika masih ada transaksi yang belum commit atau abort
     * @throws LoggerErrorException jika record log gagal ditulis atau di-flush
     * @throws RuntimeException jika storage gagal; perubahan yang sudah dibatalkan akan dikembalikan
     */
    public int recoverToTransaction(int idTransaction) {
        restoreLock.writeLock().lock();
        try {
            List<WALRecord> all = logger.getLogsSorted();
            for (int i = 0; i < all.size(); i++) {
                WALRecord r = all.get(i);
                if (r.getIdTransaction() == idTransaction && r.getLogType() == LogType.COMMIT) {
                    return restore(all, i + 1);
                }
            }
            throw new IllegalArgumentException("T" + idTransaction + " has not committed");
        } finally {
            restoreLock.writeLock().unlock();
        }
    }

    /**
     * Me-rollback satu transaksi. Pemanggil sudah memegang monitor transaksi tersebut atau lock
     * pemulihan secara eksklusif.
     */
    private boolean rollbackLocked(int idTransaction) {
        Deque<Step> todo = pending.get(idTransaction);
        if (todo == null) {
            List<WALRecord> records = logger.getLogsByTransaction(idTransaction);
            if (records.isEmpty() || has(records, LogType.COMMIT)) {
                return false;
            }
            if (has(records, LogType.ABORT)) {
                logger.flushAll(); // panggilan sebelumnya mungkin gagal saat mem-flush ABORT
                return true;
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
        return true;
    }

    /**
     * Membatalkan perubahan setelah posisi {@code cut}, serta perubahan dari transaksi yang belum
     * commit sebelum posisi itu.
     */
    private int restore(List<WALRecord> all, int cut) {
        Set<Integer> finished = new HashSet<>();
        Set<Integer> seen = new HashSet<>();
        Set<Integer> committedBeforeCut = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            WALRecord r = all.get(i);
            seen.add(r.getIdTransaction());
            if (r.getLogType() == LogType.COMMIT || r.getLogType() == LogType.ABORT) {
                finished.add(r.getIdTransaction());
            }
            if (r.getLogType() == LogType.COMMIT && i < cut) {
                committedBeforeCut.add(r.getIdTransaction());
            }
        }
        seen.removeAll(finished);
        seen.remove(0); // record checkpoint tidak terikat ke transaksi mana pun
        if (!seen.isEmpty()) {
            throw new IllegalStateException("Transactions still running: " + seen);
        }

        List<WALRecord> toUndo = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            WALRecord r = all.get(i);
            boolean keep = i < cut && committedBeforeCut.contains(r.getIdTransaction());
            if (r.getLogType().isDataChange() && !keep) {
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
            // Gagal di tengah: batalkan lagi apa yang sudah dipulihkan transaksi sistem ini.
            pending.remove(systemTx);
            try {
                rollbackLocked(systemTx);
            } catch (RuntimeException inner) {
                e.addSuppressed(inner);
            }
            throw e;
        }
        logger.logTransactionEvent(systemTx, LogType.COMMIT);
        logger.flushAll();
        return toUndo.size();
    }

    /**
     * Menulis record kompensasi untuk satu perubahan (cukup sekali, meskipun dicoba ulang), lalu
     * mengembalikan isi lamanya.
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
                            r.getIdRecord(),
                            r.getAfterValue(),
                            r.getBeforeValue());
        }
        storage.applyUndo(
                r.getTableName(), r.getIdRecord(), r.getBeforeValue(), step.compensationLsn);
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
        int compensationLsn = -1;

        Step(WALRecord original) {
            this.original = original;
        }
    }
}
