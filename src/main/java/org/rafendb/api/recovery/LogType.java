package org.rafendb.api.recovery;

/**
 * Jenis record pada Write-Ahead Log (WAL) sesuai diagram kelas FRM.
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
    /** Record ditambahkan; beforeImage bernilai empty/null. */
    INSERT,
    /** Record diubah; nilai sebelum dan sesudah terisi. */
    UPDATE,
    /** Record dihapus; afterImage bernilai empty/null. */
    DELETE,
    /** Checkpoint dimulai. */
    CHECKPOINT_BEGIN,
    /** Checkpoint selesai. */
    CHECKPOINT_END;

    /**
     * Mengecek apakah record bertipe ini mengubah data tabel sehingga bisa dibatalkan (undo) atau diulang (redo).
     *
     * @return {@code true} untuk {@link #INSERT}, {@link #UPDATE}, dan {@link #DELETE}
     */
    public boolean isDataChange() {
        return this == INSERT || this == UPDATE || this == DELETE;
    }

    /**
     * Mengecek apakah record bertipe ini merupakan peristiwa transaksi siklus hidup.
     *
     * @return {@code true} untuk {@link #BEGIN}, {@link #COMMIT}, dan {@link #ABORT}
     */
    public boolean isTransactionEvent() {
        return this == BEGIN || this == COMMIT || this == ABORT;
    }

    /**
     * Mengecek apakah record bertipe ini merupakan penanda checkpoint.
     *
     * @return {@code true} untuk {@link #CHECKPOINT_BEGIN} dan {@link #CHECKPOINT_END}
     */
    public boolean isCheckpoint() {
        return this == CHECKPOINT_BEGIN || this == CHECKPOINT_END;
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
