package org.rafendb.recovery.restart;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.Logger;
import org.rafendb.recovery.log.WALRecord;
import org.rafendb.recovery.rollback.UndoStorage;

/**
 * Mock Crash Recovery Engine untuk Milestone 1 (FR-07) sesuai diagram kelas FRM.
 *
 * <p>Menyediakan implementasi mock in-memory untuk fase Redo dan Undo pada prosedur pemulihan kegagalan (crash recovery),
 * sebelum implementasi protokol ARIES penuh dengan fuzzy checkpointing diintegrasikan pada milestone berikutnya.
 */
public class CrashRecoveryEngine {

    private final Logger logger;
    private final UndoStorage storage;
    private boolean crashRecovered = false;
    private boolean redoExecuted = false;
    private boolean undoExecuted = false;

    /**
     * Konstruktor default mock CrashRecoveryEngine tanpa dependensi.
     */
    public CrashRecoveryEngine() {
        this(null, null);
    }

    /**
     * Membuat instance mock CrashRecoveryEngine dengan logger dan storage adapter.
     *
     * @param logger logger WAL mock
     * @param storage storage adapter untuk menerapkan perubahan data
     */
    public CrashRecoveryEngine(Logger logger, UndoStorage storage) {
        this.logger = logger;
        this.storage = storage;
    }

    /**
     * Mock eksekusi prosedur crash recovery (menjalankan fase Redo lalu Undo).
     *
     * @return true jika pemulihan crash berhasil
     */
    public boolean recoverFromCrash() {
        boolean r = redo();
        boolean u = undo();
        this.crashRecovered = r && u;
        return this.crashRecovered;
    }

    /**
     * Mock eksekusi fase Redo.
     *
     * <p>Mengulangi (redo) perubahan dari transaksi yang telah committed untuk memastikan
     * persistensi data.
     *
     * @return true jika fase Redo berhasil
     */
    public boolean redo() {
        this.redoExecuted = true;
        if (logger == null || storage == null) {
            return true;
        }

        try {
            List<WALRecord> allLogs = logger.getLogsSorted();
            Set<Integer> committedTxs = new HashSet<>();
            for (WALRecord rec : allLogs) {
                if (rec.getLogType() == LogType.COMMIT) {
                    committedTxs.add(rec.getIdTransaction());
                }
            }

            for (WALRecord rec : allLogs) {
                if (rec.getLogType().isDataChange()
                        && committedTxs.contains(rec.getIdTransaction())) {
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
     * Mock eksekusi fase Undo.
     *
     * <p>Membatalkan (undo) perubahan dari transaksi yang belum committed (loser transactions)
     * saat crash terjadi.
     *
     * @return true jika fase Undo berhasil
     */
    public boolean undo() {
        this.undoExecuted = true;
        if (logger == null || storage == null) {
            return true;
        }

        try {
            List<WALRecord> allLogs = logger.getLogsSorted();
            Set<Integer> committedTxs = new HashSet<>();
            Set<Integer> allTxs = new HashSet<>();
            for (WALRecord rec : allLogs) {
                allTxs.add(rec.getIdTransaction());
                if (rec.getLogType() == LogType.COMMIT) {
                    committedTxs.add(rec.getIdTransaction());
                }
            }

            Set<Integer> uncommittedTxs = new HashSet<>(allTxs);
            uncommittedTxs.removeAll(committedTxs);

            for (int i = allLogs.size() - 1; i >= 0; i--) {
                WALRecord rec = allLogs.get(i);
                if (rec.getLogType().isDataChange()
                        && uncommittedTxs.contains(rec.getIdTransaction())) {
                    storage.applyUndo(
                            rec.getTableName(),
                            rec.getIdRecord(),
                            rec.getBeforeVal(),
                            rec.getLSN());
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return true jika recoverFromCrash telah dieksekusi
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
}
