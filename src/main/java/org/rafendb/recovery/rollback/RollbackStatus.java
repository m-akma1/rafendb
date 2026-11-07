package org.rafendb.recovery.rollback;

/**
 * Hasil permintaan rollback dari {@link RollbackManager#rollback(int)}, agar CCM bisa membedakan
 * rollback yang baru selesai, permintaan ulang, dan permintaan yang tidak bisa dilayani.
 *
 * <p>Immutable dan thread-safe.
 */
public enum RollbackStatus {
    /** Semua perubahan transaksi barusan dibatalkan dan ABORT sudah tersimpan permanen. */
    ROLLED_BACK,
    /** Transaksi sudah di-rollback sebelumnya; tidak ada data yang diubah lagi. */
    ALREADY_ABORTED,
    /** Transaksi sudah commit sehingga tidak boleh di-rollback; tidak ada yang diubah. */
    ALREADY_COMMITTED,
    /** Log tidak memiliki record apa pun untuk transaksi ini; tidak ada yang diubah. */
    UNKNOWN_TRANSACTION;

    /**
     * Mengecek apakah transaksi kini berstatus aborted, baik barusan maupun sebelumnya.
     *
     * @return {@code true} untuk {@link #ROLLED_BACK} dan {@link #ALREADY_ABORTED}
     */
    public boolean isAborted() {
        return this == ROLLED_BACK || this == ALREADY_ABORTED;
    }
}
