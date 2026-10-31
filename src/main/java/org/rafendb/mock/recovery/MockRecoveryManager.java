package org.rafendb.mock.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.rafendb.api.recovery.ILogFlusher;
import org.rafendb.api.recovery.LogPayload;
import org.rafendb.api.recovery.LoggerErrorException;
import org.rafendb.api.recovery.RecoveryManager;
import org.rafendb.common.txn.TxStatus;

/**
 * In-memory mock implementation of {@link RecoveryManager} and {@link ILogFlusher} for Milestone 1.
 *
 * <p>Simulates all recovery facade operations in memory without touching disk storage, allowing
 * other components (CCM, QP, SM) to run drivers and tests independently.
 */
public class MockRecoveryManager implements RecoveryManager, ILogFlusher {

    private final List<LogPayload> writtenLogs = Collections.synchronizedList(new ArrayList<>());
    private final Map<Integer, TxStatus> txStatuses = new ConcurrentHashMap<>();
    private final AtomicLong mockLsnCounter = new AtomicLong(0);
    private volatile long lastFlushedLsn = 0;
    private volatile boolean checkpointCreated = false;
    private volatile boolean crashRecovered = false;
    private volatile long lastRecoveredTimestamp = 0;
    private volatile boolean redoExecuted = false;
    private volatile boolean undoExecuted = false;

    @Override
    public long writeLog(LogPayload payload) throws LoggerErrorException {
        if (payload == null) {
            return -1;
        }
        long lsn = mockLsnCounter.incrementAndGet();
        writtenLogs.add(payload);
        return lsn;
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
        this.checkpointCreated = true;
        return true;
    }

    @Override
    public boolean recoverToPointInTime(long targetTimestamp) {
        this.lastRecoveredTimestamp = targetTimestamp;
        return true;
    }

    @Override
    public boolean recoverToPointInTime() {
        return recoverToPointInTime(System.currentTimeMillis());
    }

    @Override
    public boolean recoverFromCrash() {
        this.crashRecovered = true;
        redo();
        undo();
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

    @Override
    public void flushLogUntil(long lsn) throws LoggerErrorException {
        forceThrough(lsn);
    }

    @Override
    public void forceThrough(long lsn) {
        this.lastFlushedLsn = Math.max(lastFlushedLsn, lsn);
    }

    @Override
    public TxStatus getTransactionStatus(int idTransaction) {
        return txStatuses.get(idTransaction);
    }

    /**
     * @return true jika checkpoint telah dibuat
     */
    public boolean isCheckpointCreated() {
        return checkpointCreated;
    }

    /**
     * @return true jika crash recovery telah dijalankan
     */
    public boolean isCrashRecovered() {
        return crashRecovered;
    }

    /**
     * @return timestamp batas pemulihan PITR terakhir
     */
    public long getLastRecoveredTimestamp() {
        return lastRecoveredTimestamp;
    }

    /**
     * @return LSN terakhir yang di-flush / dipaksa ke storage
     */
    public long getLastFlushedLsn() {
        return lastFlushedLsn;
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

    /**
     * Mengembalikan daftar salinan seluruh log yang telah dicatat di mock memory.
     *
     * @return unmodifiable list dari log payload
     */
    public List<LogPayload> getWrittenLogs() {
        return List.copyOf(writtenLogs);
    }

    /**
     * Menyaring log berdasarkan ID transaksi untuk keperluan audit dan pengujian.
     *
     * @param idTransaction id transaksi yang dicari
     * @return list log payload milik transaksi tersebut
     */
    public List<LogPayload> getLogsByTransaction(int idTransaction) {
        synchronized (writtenLogs) {
            return writtenLogs.stream()
                    .filter(log -> log.idTransaction() == idTransaction)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Menghapus seluruh data mock in-memory.
     */
    public void clear() {
        writtenLogs.clear();
        txStatuses.clear();
        mockLsnCounter.set(0);
        lastFlushedLsn = 0;
        checkpointCreated = false;
        crashRecovered = false;
        lastRecoveredTimestamp = 0;
        redoExecuted = false;
        undoExecuted = false;
    }
}
