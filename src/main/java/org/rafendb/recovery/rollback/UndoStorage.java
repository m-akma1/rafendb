package org.rafendb.recovery.rollback;

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
    void applyUndo(String tableName, int idRecord, String image, int lsn);
}
