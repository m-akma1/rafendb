package org.rafendb.recovery.log;

/**
 * Interface flusher log sesuai diagram kelas FRM.
 *
 * <p>Menyediakan operasi sinkronisasi log ke stable storage hingga nomor LSN tertentu, menjamin
 * aturan Write-Ahead Logging (WAL) terpenuhi sebelum halaman buffer ditulis ke disk.
 */
public interface ILogFlusher extends org.rafendb.api.recovery.ILogFlusher {

    /**
     * Mem-flush semua record log sampai dengan LSN yang ditentukan ke stable storage.
     *
     * @param lsn batas nomor urut log yang harus dipastikan sudah permanen di disk
     * @throws LoggerErrorException jika terjadi kegagalan I/O saat melakukan flush
     */
    @Override
    void flushLogUntil(int lsn) throws LoggerErrorException;
}
