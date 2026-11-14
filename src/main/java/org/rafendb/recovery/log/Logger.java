package org.rafendb.recovery.log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.rafendb.api.storage.WalSync;
import org.rafendb.common.tuple.Rid;
import org.rafendb.recovery.rollback.RollbackLog;

/**
 * Write-Ahead Logging (WAL) Logger sesuai diagram kelas Failure Recovery Manager (FRM).
 *
 * <p>Mencatat seluruh peristiwa transaksi (BEGIN, COMMIT, ABORT) dan perubahan data (INSERT, UPDATE, DELETE)
 * secara sekuensial dengan penomoran LSN monotonik. Menjamin aturan Write-Ahead Logging (WAL) dan ketahanan
 * (durability) data dengan mendukung persistensi ke file log dan pemaksaan buffer ke media penyimpanan stabil
 * (force log / sync) sebelum halaman kotor ditulis ke disk.
 *
 * <p>Thread-safe.
 */
public class Logger implements ILogFlusher, WalSync, RollbackLog, AutoCloseable {

    private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;

    private final List<WALRecord> records = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong currentLSN = new AtomicLong(0);
    private volatile long lastCommittedLSN = 0;
    private final String logFilePath;

    private FileOutputStream fileOutputStream;
    private FileChannel fileChannel;
    private DataOutputStream dataOutputStream;

    /**
     * Konstruktor default Logger (hanya in-memory tanpa path file).
     */
    public Logger() {
        this("");
    }

    /**
     * Membuat Logger dengan path file log.
     *
     * <p>Jika file log telah ada sebelumnya di disk, seluruh record WAL yang tersimpan akan dimuat
     * kembali ke memori dan LSN disesuaikan ke nomor tertinggi.
     *
     * @param logFilePath path ke file log di disk
     */
    public Logger(String logFilePath) {
        this.logFilePath = logFilePath != null ? logFilePath : "";
        if (!this.logFilePath.isEmpty()) {
            loadExistingLogsFromFile();
        }
    }

    /**
     * Membuat Logger dengan objek {@link Path}.
     *
     * @param logPath path ke file log
     */
    public Logger(Path logPath) {
        this(logPath != null ? logPath.toString() : "");
    }

    /**
     * Memuat record WAL yang telah tersimpan dari file log di disk jika ada.
     * Mampu mendeteksi dan menangani torn-tail record yang belum selesai ditulis saat crash.
     */
    private synchronized void loadExistingLogsFromFile() {
        File file = new File(logFilePath);
        if (!file.exists() || !file.isFile() || file.length() == 0) {
            return;
        }

        long validOffset = 0;
        boolean tornTailDetected = false;
        try (DataInputStream in =
                new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            long maxLsn = 0;
            long lastCommit = 0;
            while (true) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    if (validOffset < file.length()) {
                        tornTailDetected = true;
                    }
                    break;
                }
                if (length <= 0 || length > MAX_RECORD_BYTES) {
                    tornTailDetected = true;
                    break;
                }
                byte[] buffer = in.readNBytes(length);
                if (buffer.length < length) {
                    // Torn record pada akhir file akibat crash sistem mendadak
                    tornTailDetected = true;
                    break;
                }
                try {
                    WALRecord record = WALRecord.deserialize(buffer);
                    records.add(record);
                    validOffset += Integer.BYTES + (long) length;
                    if (record.getLSN() > maxLsn) {
                        maxLsn = record.getLSN();
                    }
                    if (record.getLogType() == LogType.COMMIT) {
                        lastCommit = record.getLSN();
                    }
                } catch (IllegalArgumentException e) {
                    // Record terdistorsi/checksum mismatch di batas akhir log
                    tornTailDetected = true;
                    break;
                }
            }
            currentLSN.set(maxLsn);
            this.lastCommittedLSN = lastCommit;
        } catch (IOException e) {
            throw new LoggerErrorException(
                    "Failed to load existing WAL records from: " + logFilePath, e);
        }

        if (tornTailDetected) {
            truncateCorruptTail(file.toPath(), validOffset);
        }
    }

    /** Memotong byte korup/terpotong di akhir file WAL setelah crash. */
    private void truncateCorruptTail(Path path, long validOffset) {
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ch.truncate(validOffset);
            ch.force(true);
        } catch (IOException e) {
            throw new LoggerErrorException(
                    "Failed to truncate corrupt WAL tail at offset " + validOffset, e);
        }
    }

    /**
     * Membuka output stream ke file log jika belum terbuka.
     */
    private synchronized void ensureOutputStreamOpen() throws IOException {
        if (dataOutputStream != null) {
            return;
        }
        File file = new File(logFilePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        this.fileOutputStream = new FileOutputStream(file, true);
        this.fileChannel = fileOutputStream.getChannel();
        this.dataOutputStream = new DataOutputStream(new BufferedOutputStream(fileOutputStream));
    }

    /**
     * Menuliskan record WAL ke disk secara persisten.
     */
    private synchronized void writeRecordToDisk(WALRecord record) {
        if (logFilePath.isEmpty()) {
            return;
        }
        try {
            ensureOutputStreamOpen();
            byte[] serialized = record.serialize();
            dataOutputStream.writeInt(serialized.length);
            dataOutputStream.write(serialized);
            dataOutputStream.flush();
        } catch (IOException e) {
            throw new LoggerErrorException(
                    "Failed to append WAL record to disk: " + logFilePath, e);
        }
    }

    /**
     * Mengembalikan LSN dari transaksi terakhir yang committed.
     *
     * @return LSN commit terakhir
     */
    public long getLastCommittedLSN() {
        return lastCommittedLSN;
    }

    /**
     * Mengatur nilai LSN transaksi terakhir yang commit.
     *
     * @param lsn nomor LSN commit
     */
    public void setLastCommittedLSN(long lsn) {
        this.lastCommittedLSN = lsn;
    }

    /**
     * Mengatur LSN commit terakhir ke LSN saat ini.
     */
    public void setLastCommittedLSN() {
        this.lastCommittedLSN = currentLSN.get();
    }

    /**
     * Mengembalikan nomor LSN tertinggi yang saat ini telah dialokasikan.
     *
     * @return nomor LSN saat ini
     */
    public long getCurrentLSN() {
        return currentLSN.get();
    }

    /**
     * Mem-flush buffer log ke stable storage.
     */
    public void flush() {
        flushAll();
    }

    /**
     * Menyimpan permanen seluruh record log yang berada di buffer memori ke penyimpanan fisik stabil.
     */
    @Override
    public synchronized void flushAll() {
        if (dataOutputStream != null) {
            try {
                dataOutputStream.flush();
                if (fileChannel != null) {
                    fileChannel.force(false);
                } else if (fileOutputStream != null) {
                    fileOutputStream.getFD().sync();
                }
            } catch (IOException e) {
                throw new LoggerErrorException(
                        "Failed to flush WAL records to disk: " + logFilePath, e);
            }
        }
    }

    /**
     * Mem-flush semua record log sampai dengan LSN yang ditentukan ke stable storage.
     *
     * @param lsn batas nomor urut log yang harus dipastikan sudah permanen di disk
     * @throws LoggerErrorException jika terjadi kegagalan I/O saat melakukan flush
     */
    @Override
    public void flushLogUntil(long lsn) throws LoggerErrorException {
        flushAll();
    }

    /**
     * Sinkronisasi paksa record log sampai dengan LSN tertentu ke disk (implementasi {@link WalSync}).
     *
     * @param lsn batas LSN log
     */
    @Override
    public void forceThrough(long lsn) {
        flushAll();
    }

    @Override
    public List<WALRecord> getLogsSorted() {
        synchronized (records) {
            return List.copyOf(records);
        }
    }

    /**
     * Alias untuk {@link #getLogsSorted()} sesuai konvensi class diagram FRM.
     *
     * @return daftar seluruh record log terurut LSN
     */
    public List<WALRecord> readAllLogs() {
        return getLogsSorted();
    }

    /**
     * Menambahkan record WAL secara langsung ke log buffer dan disk.
     *
     * @param record record yang akan ditambahkan
     * @return nomor LSN record
     * @throws LoggerErrorException jika terjadi kegagalan penulisan log
     */
    public synchronized long appendRecord(WALRecord record) throws LoggerErrorException {
        if (record == null) {
            return -1L;
        }
        writeRecordToDisk(record);
        records.add(record);
        if (record.getLsn() > currentLSN.get()) {
            currentLSN.set(record.getLsn());
        }
        if (record.getLogType() == LogType.COMMIT) {
            lastCommittedLSN = record.getLsn();
        }
        return record.getLsn();
    }

    @Override
    public List<WALRecord> getLogsByTransaction(int idTransaction) {
        synchronized (records) {
            return records.stream().filter(r -> r.getIdTransaction() == idTransaction).toList();
        }
    }

    @Override
    public synchronized void logTransactionEvent(int idTransaction, LogType logType) {
        long prevLsn = currentLSN.get();
        long lsn = currentLSN.incrementAndGet();
        long now = System.currentTimeMillis();
        WALRecord record = new WALRecord(lsn, idTransaction, logType, now, null, -1, null, null);
        try {
            writeRecordToDisk(record);
        } catch (RuntimeException e) {
            currentLSN.set(prevLsn);
            throw e;
        }
        records.add(record);
        if (logType == LogType.COMMIT) {
            lastCommittedLSN = lsn;
        }
    }

    /**
     * Mencatat perubahan data menggunakan {@link Rid}.
     *
     * @param idTransaction id transaksi
     * @param logType tipe operasi
     * @param timestamp waktu pencatatan
     * @param tableName nama tabel
     * @param rid alamat baris pada tabel
     * @param beforeVal nilai sebelum
     * @param afterVal nilai sesudah
     * @return nomor LSN yang dialokasikan
     */
    public synchronized long logDataChange(
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            Rid rid,
            String beforeVal,
            String afterVal) {
        return logDataChange(
                idTransaction, logType, timestamp, tableName, 0L, rid, beforeVal, afterVal);
    }

    /**
     * Mencatat perubahan data menggunakan {@link Rid} dan generasi tabel persisten.
     *
     * @param idTransaction id transaksi
     * @param logType tipe operasi
     * @param timestamp waktu pencatatan
     * @param tableName nama tabel
     * @param tableGeneration generasi tabel saat perubahan dicatat
     * @param rid alamat baris pada tabel
     * @param beforeVal nilai sebelum
     * @param afterVal nilai sesudah
     * @return nomor LSN yang dialokasikan
     */
    public synchronized long logDataChange(
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            long tableGeneration,
            Rid rid,
            String beforeVal,
            String afterVal) {
        long prevLsn = currentLSN.get();
        long lsn = currentLSN.incrementAndGet();
        WALRecord record =
                new WALRecord(
                        lsn,
                        idTransaction,
                        logType,
                        timestamp,
                        tableName,
                        tableGeneration,
                        rid,
                        beforeVal,
                        afterVal);
        try {
            writeRecordToDisk(record);
        } catch (RuntimeException e) {
            currentLSN.set(prevLsn);
            throw e;
        }
        records.add(record);
        return lsn;
    }

    @Override
    public synchronized long logDataChange(
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal) {
        long prevLsn = currentLSN.get();
        long lsn = currentLSN.incrementAndGet();
        WALRecord record =
                new WALRecord(
                        lsn,
                        idTransaction,
                        logType,
                        timestamp,
                        tableName,
                        idRecord,
                        beforeVal,
                        afterVal);
        try {
            writeRecordToDisk(record);
        } catch (RuntimeException e) {
            currentLSN.set(prevLsn);
            throw e;
        }
        records.add(record);
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
     * Menampilkan semua log ke standard output dalam urutan LSN.
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
    public synchronized void close() throws IOException {
        if (dataOutputStream != null) {
            try {
                flushAll();
            } finally {
                try {
                    dataOutputStream.close();
                } finally {
                    dataOutputStream = null;
                    fileOutputStream = null;
                    fileChannel = null;
                }
            }
        }
    }
}
