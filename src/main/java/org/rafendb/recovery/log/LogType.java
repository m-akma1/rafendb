package org.rafendb.recovery.log;

/**
 * Jenis sebuah {@link WALRecord}, sesuai class diagram FRM.
 *
 * <p>Immutable dan thread-safe.
 */
public enum LogType {
    /** Transaksi dimulai. */
    BEGIN,
    /** Transaksi commit; ditulis dan di-flush sebelum commit dilaporkan berhasil. */
    COMMIT,
    /** Rollback transaksi selesai; ditulis setelah semua perubahan dibatalkan. */
    ABORT,
    /** Record ditambahkan; {@code before_val} bernilai {@code null}. */
    INSERT,
    /** Record diubah; nilai sebelum dan sesudah sama-sama terisi. */
    UPDATE,
    /** Record dihapus; {@code after_val} bernilai {@code null}. */
    DELETE,
    /** Checkpoint dimulai. */
    CHECKPOINT_BEGIN,
    /** Checkpoint selesai. */
    CHECKPOINT_END;

    /**
     * Mengecek apakah record bertipe ini mengubah data tabel sehingga bisa dibatalkan (undo).
     *
     * @return {@code true} untuk {@link #INSERT}, {@link #UPDATE}, dan {@link #DELETE}
     */
    public boolean isDataChange() {
        return this == INSERT || this == UPDATE || this == DELETE;
    }

    /**
     * Mengembalikan tipe perubahan yang membatalkan perubahan bertipe ini: INSERT dan DELETE saling
     * bertukar, UPDATE tetap UPDATE.
     *
     * @return tipe perubahan kebalikannya
     * @throws IllegalStateException jika tipe ini bukan perubahan data
     */
    public LogType inverse() {
        return switch (this) {
            case INSERT -> DELETE;
            case DELETE -> INSERT;
            case UPDATE -> UPDATE;
            default -> throw new IllegalStateException(this + " is not a data change");
        };
    }
}
