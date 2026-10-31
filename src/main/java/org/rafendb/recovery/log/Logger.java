package org.rafendb.recovery.log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.rafendb.api.storage.WalSync;
import org.rafendb.recovery.rollback.RollbackLog;

/**
 * Mock WAL Logger untuk Milestone 1 (FR-02) sesuai diagram kelas FRM.
 *
 * <p>Menyediakan implementasi in-memory mock untuk komponen WAL Logger tanpa operasi I/O disk nyata,
 * sehingga modul lain (Rollback, Checkpoint, Facade) dapat beroperasi secara independen pada Milestone 1.
 */
public class Logger implements ILogFlusher, WalSync, RollbackLog, AutoCloseable {

    private final List<WALRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger currentLSN = new AtomicInteger(0);
    private volatile int lastCommittedLSN = 0;
    private final String logFilePath;

    /**
     * Konstruktor default mock Logger.
     */
    public Logger() {
        this("");
    }

    /**
     * Membuat mock Logger dengan path file log.
     *
     * @param logFilePath path ke file log
     */
    public Logger(String logFilePath) {
        this.logFilePath = logFilePath != null ? logFilePath : "";
    }

    /**
     * Membuat mock Logger dengan path objek {@link Path}.
     *
     * @param logPath path ke file log
     */
    public Logger(Path logPath) {
        this(logPath != null ? logPath.toString() : "");
    }

    /**
     * Mengembalikan LSN dari transaksi terakhir yang committed.
     *
     * @return LSN commit terakhir
     */
    public int getLastCommittedLSN() {
        return lastCommittedLSN;
    }

    /**
     * Mengatur nilai LSN transaksi terakhir yang commit.
     *
     * @param lsn nomor LSN commit
     */
    public void setLastCommittedLSN(int lsn) {
        this.lastCommittedLSN = lsn;
    }

    /**
     * Mengatur LSN commit terakhir ke LSN saat ini.
     */
    public void setLastCommittedLSN() {
        this.lastCommittedLSN = currentLSN.get();
    }

    /**
     * Mock flush log buffer ke stable storage.
     */
    public void flush() {}

    @Override
    public void flushAll() {}

    @Override
    public void flushLogUntil(int lsn) {}

    @Override
    public void forceThrough(long lsn) {}

    @Override
    public List<WALRecord> getLogsSorted() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    @Override
    public List<WALRecord> getLogsByTransaction(int idTransaction) {
        synchronized (records) {
            return records.stream().filter(r -> r.getIdTransaction() == idTransaction).toList();
        }
    }

    @Override
    public void logTransactionEvent(int idTransaction, LogType logType) {
        int lsn = currentLSN.incrementAndGet();
        long now = System.currentTimeMillis();
        records.add(new WALRecord(lsn, idTransaction, logType, now, null, -1, null, null));
        if (logType == LogType.COMMIT) {
            lastCommittedLSN = lsn;
        }
    }

    @Override
    public int logDataChange(
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal) {
        int lsn = currentLSN.incrementAndGet();
        records.add(
                new WALRecord(
                        lsn,
                        idTransaction,
                        logType,
                        timestamp,
                        tableName,
                        idRecord,
                        beforeVal,
                        afterVal));
        return lsn;
    }

    @Override
    public int getHighestTransactionId() {
        synchronized (records) {
            int maxId = 0;
            for (WALRecord rec : records) {
                if (rec.getIdTransaction() > maxId) {
                    maxId = rec.getIdTransaction();
                }
            }
            return maxId;
        }
    }

    /**
     * Menampilkan semua log ke standard output.
     */
    public void displayLogs() {
        List<WALRecord> logs = getLogsSorted();
        System.out.println("=== WAL LOGS (" + logs.size() + " records) ===");
        for (WALRecord record : logs) {
            record.printWALRecord();
        }
        System.out.println("==============================================");
    }

    /**
     * Menampilkan log transaksi tertentu ke standard output.
     *
     * @param idTransaction id transaksi
     */
    public void displayLogsByTransaction(int idTransaction) {
        List<WALRecord> logs = getLogsByTransaction(idTransaction);
        System.out.println(
                "=== WAL LOGS FOR TX " + idTransaction + " (" + logs.size() + " records) ===");
        for (WALRecord record : logs) {
            record.printWALRecord();
        }
        System.out.println("==================================================");
    }

    /**
     * Mengembalikan path file log.
     *
     * @return path file log
     */
    public String getLogFilePath() {
        return logFilePath;
    }

    @Override
    public void close() throws IOException {}
}
