package org.rafendb.recovery.rollback;

import java.util.List;
import org.rafendb.recovery.log.LogType;
import org.rafendb.recovery.log.LoggerErrorException;
import org.rafendb.recovery.log.WALRecord;

/**
 * Bagian dari {@code Logger} milik FR2 yang dibutuhkan FR3. Nama method dan parameternya mengikuti
 * class {@code Logger} di class diagram FRM, jadi {@code Logger} cukup menulis {@code implements
 * RollbackLog}. Implementasinya wajib thread-safe.
 */
public interface RollbackLog {

    /**
     * Mengembalikan semua record, terurut berdasarkan LSN.
     *
     * @return daftar record, tidak pernah null; kosong jika log belum berisi record
     */
    List<WALRecord> getLogsSorted();

    /**
     * Mengembalikan record milik satu transaksi, terurut berdasarkan LSN.
     *
     * @param idTransaction id transaksi
     * @return daftar record, tidak pernah null; kosong jika transaksi belum menulis apa pun
     */
    List<WALRecord> getLogsByTransaction(int idTransaction);

    /**
     * Menambahkan record BEGIN, COMMIT, atau ABORT. Belum di-flush ke disk.
     *
     * @param idTransaction id transaksi
     * @param logType BEGIN, COMMIT, atau ABORT; tidak boleh null
     * @throws LoggerErrorException jika record gagal ditambahkan
     */
    void logTransactionEvent(int idTransaction, LogType logType);

    /**
     * Menambahkan record INSERT, UPDATE, atau DELETE. Belum di-flush ke disk.
     *
     * @param idTransaction id transaksi
     * @param logType INSERT, UPDATE, atau DELETE; tidak boleh null
     * @param timestamp waktu perubahan terjadi, dalam milidetik epoch
     * @param tableName tabel yang diubah, tidak boleh null
     * @param idRecord record yang diubah
     * @param beforeVal isi record sebelum perubahan, {@code null} untuk INSERT
     * @param afterVal isi record sesudah perubahan, {@code null} untuk DELETE
     * @return LSN record yang baru
     * @throws LoggerErrorException jika record gagal ditambahkan
     */
    long logDataChange(
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal);

    /**
     * Menyimpan permanen (flush) semua record yang sudah ditambahkan ke stable storage.
     *
     * @throws LoggerErrorException jika log gagal di-flush
     */
    void flushAll();

    /**
     * Mengembalikan id transaksi tertinggi di log.
     *
     * @return id tersebut, atau {@code 0} jika log kosong
     */
    int getHighestTransactionId();
}
