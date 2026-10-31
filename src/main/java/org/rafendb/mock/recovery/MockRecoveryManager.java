package org.rafendb.mock.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.rafendb.api.recovery.LogPayload;
import org.rafendb.api.recovery.LoggerErrorException;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.txn.TxStatus;

/**
 * In-memory mock implementation of {@link RecoveryManager} for Milestone 1.
 *
 * <p>Simulates all recovery facade operations in memory without touching disk storage, allowing
 * other components (CCM, QP) to run drivers and tests independently.
 */
public class MockRecoveryManager implements RecoveryManager {

    private final List<LogPayload> writtenLogs = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, TxStatus> txStatuses = new ConcurrentHashMap<>();
    private volatile long lastFlushedLsn = 0;
    private volatile boolean redoExecuted = false;
    private volatile boolean undoExecuted = false;

    @Override
    public boolean writeLog(LogPayload payload) throws LoggerErrorException {
        if (payload == null) {
            return false;
        }
        writtenLogs.add(payload);
        return true;
    }

    @Override
    public boolean beginTransaction(int idTransaction) {
        txStatuses.put(idTransaction, TxStatus.ACTIVE);
        return true;
    }

    @Override
    public boolean commitTransaction(int idTransaction) {
        txStatuses.put(idTransaction, TxStatus.COMMITTED);
        return true;
    }

    @Override
    public boolean rollbackTransaction(int idTransaction) {
        txStatuses.put(idTransaction, TxStatus.ABORTED);
        return true;
    }

    @Override
    public boolean createCheckpoint() {
        return true;
    }

    @Override
    public boolean recoverToPointInTime(long targetTimestamp) {
        return true;
    }

    @Override
    public boolean recoverToPointInTime() {
        return true;
    }

    @Override
    public boolean recoverFromCrash() {
        return true;
    }

    @Override
    public boolean redo() {
        this.redoExecuted = true;
        return true;
    }

    @Override
    public boolean undo() {
        this.undoExecuted = true;
        return true;
    }

    /**
     * @return true jika redo telah dipanggil
     */
    public boolean isRedoExecuted() {
        return redoExecuted;
    }

    /**
     * @return true jika undo telah dipanggil
     */
    public boolean isUndoExecuted() {
        return undoExecuted;
    }

    @Override
    public TxStatus getTransactionStatus(int idTransaction) {
        return txStatuses.get(idTransaction);
    }

    @Override
    public void forceThrough(long lsn) {
        this.lastFlushedLsn = Math.max(lastFlushedLsn, lsn);
    }

    /**
     * Mengembalikan daftar salinan log yang telah dicatat di mock memory.
     *
     * @return unmodifiable list dari log payload
     */
    public List<LogPayload> getWrittenLogs() {
        return List.copyOf(writtenLogs);
    }

    /**
     * Menghapus seluruh data mock in-memory.
     */
    public void clear() {
        writtenLogs.clear();
        txStatuses.clear();
        lastFlushedLsn = 0;
        redoExecuted = false;
        undoExecuted = false;
    }
}
