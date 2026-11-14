package org.rafendb.recovery.rollback;

import org.rafendb.common.tuple.Rid;

/**
 * Yang dibutuhkan FR3 dari Storage Manager untuk membatalkan sebuah perubahan. Facade FRM akan
 * menyambungkannya ke {@code StorageManager.applyRecovery(table, rid, image, lsn)} setelah format
 * id record dan nilai disepakati dengan SM. Implementasinya wajib thread-safe.
 */
@FunctionalInterface
public interface UndoStorage {

    /**
     * Mengembalikan isi record tanpa mencatat ke log. Jika {@code image} terisi, record tepat di
     * {@code idRecord} menjadi isi tersebut (dibuat baru atau ditimpa); jika {@code null}, record
     * tersebut dihapus. Idempoten: menerapkan isi yang sama dua kali memberi hasil yang sama.
     *
     * @param tableName nama tabel, tidak boleh null
     * @param idRecord id record yang dituju
     * @param image isi record yang disimpan, atau {@code null} untuk menghapus record
     * @param lsn LSN record kompensasi yang ditulis untuk undo ini
     * @throws RuntimeException jika isi record gagal diterapkan; tidak ada data yang berubah
     */
    void applyUndo(String tableName, int idRecord, String image, long lsn);

    /**
     * Sama seperti {@link #applyUndo(String, int, String, long)}, tetapi memakai alamat baris
     * {@link Rid} penuh dan generasi tabel sehingga alamat dengan slot &ge; 1000 atau block besar
     * tidak hilang. Implementasi yang tersambung ke Storage Manager sebaiknya meng-override method
     * ini; implementasi bawaan meneruskan ke versi {@code int idRecord} dengan encoding lama
     * {@code block * 1000 + slot}.
     *
     * @param tableName nama tabel, tidak boleh null
     * @param tableGeneration generasi tabel saat perubahan dicatat, {@code 0} jika tidak diketahui
     * @param rid alamat baris yang dituju, atau {@code null} jika record tidak punya alamat
     * @param image isi record yang disimpan, atau {@code null} untuk menghapus record
     * @param lsn LSN record kompensasi atau record yang di-redo
     * @throws RuntimeException jika isi record gagal diterapkan; tidak ada data yang berubah
     */
    default void applyUndo(
            String tableName, long tableGeneration, Rid rid, String image, long lsn) {
        applyUndo(tableName, legacyId(rid), image, lsn);
    }

    /**
     * Mengubah {@link Rid} ke id record lama sesuai encoding {@code WALRecord}.
     *
     * @param rid alamat baris, boleh null
     * @return {@code block * 1000 + slot}, atau {@code -1} jika {@code rid} null
     */
    static int legacyId(Rid rid) {
        return rid == null ? -1 : (int) (rid.block() * 1000 + rid.slot());
    }
}
