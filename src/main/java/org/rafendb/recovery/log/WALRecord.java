package org.rafendb.recovery.log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Satu record write-ahead log, sesuai class diagram FRM.
 *
 * <p>Record peristiwa transaksi (BEGIN, COMMIT, ABORT) dan record checkpoint memiliki nama tabel
 * {@code null}, {@code id_record} {@code -1}, serta nilai sebelum/sesudah {@code null}. INSERT
 * memiliki nilai sebelum {@code null}, sedangkan DELETE memiliki nilai sesudah {@code null}.
 *
 * <p>Tidak thread-safe karena {@link #setAfterValue} dan {@link #setLogType} dapat mengubahnya.
 * Bagikan record ke thread lain hanya setelah record selesai dibuat.
 */
public final class WALRecord {
    private final int lsn;
    private final int idTransaction;
    private LogType logType;
    private final long timestamp;
    private final String tableName;
    private final int idRecord;
    private final String beforeVal;
    private String afterVal;

    /**
     * Membuat record.
     *
     * @param lsn nomor urut log (LSN), unik dan terus naik
     * @param idTransaction transaksi yang menulis record ini
     * @param logType jenis record, tidak boleh null
     * @param timestamp waktu record ditulis, dalam milidetik epoch
     * @param tableName tabel yang diubah, atau {@code null} jika bukan perubahan data
     * @param idRecord record yang diubah, atau {@code -1} jika bukan perubahan data
     * @param beforeVal isi record sebelum perubahan, atau {@code null}
     * @param afterVal isi record sesudah perubahan, atau {@code null}
     * @throws NullPointerException jika {@code logType} null
     */
    public WALRecord(
            int lsn,
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal) {
        this.lsn = lsn;
        this.idTransaction = idTransaction;
        this.logType = Objects.requireNonNull(logType, "logType");
        this.timestamp = timestamp;
        this.tableName = tableName;
        this.idRecord = idRecord;
        this.beforeVal = beforeVal;
        this.afterVal = afterVal;
    }

    /** @return nomor urut log (LSN) */
    public int getLSN() {
        return lsn;
    }

    /** @return id transaksi yang menulis record ini */
    public int getIdTransaction() {
        return idTransaction;
    }

    /** @return jenis record, tidak pernah null */
    public LogType getLogType() {
        return logType;
    }

    /** @return waktu record ditulis, dalam milidetik epoch */
    public long getTimestamp() {
        return timestamp;
    }

    /** @return tabel yang diubah, atau {@code null} jika bukan perubahan data */
    public String getTableName() {
        return tableName;
    }

    /** @return id record yang diubah, atau {@code -1} jika bukan perubahan data */
    public int getIdRecord() {
        return idRecord;
    }

    /**
     * Mengembalikan isi record sebelum perubahan. Belum ada di class diagram; dibutuhkan untuk
     * undo.
     *
     * @return nilai sebelum, atau {@code null} untuk INSERT dan record non-data
     */
    public String getBeforeValue() {
        return beforeVal;
    }

    /**
     * Alias untuk {@link #getBeforeValue()} sesuai penamaan atribut di diagram kelas FRM.
     *
     * @return nilai sebelum
     */
    public String getBeforeVal() {
        return beforeVal;
    }

    /**
     * Mengembalikan isi record sesudah perubahan. Belum ada di class diagram; dibutuhkan untuk
     * redo.
     *
     * @return nilai sesudah, atau {@code null} untuk DELETE dan record non-data
     */
    public String getAfterValue() {
        return afterVal;
    }

    /**
     * Alias untuk {@link #getAfterValue()} sesuai penamaan atribut di diagram kelas FRM.
     *
     * @return nilai sesudah
     */
    public String getAfterVal() {
        return afterVal;
    }

    /**
     * Mengganti nilai sesudah perubahan.
     *
     * @param afterVal nilai sesudah yang baru, boleh null
     */
    public void setAfterValue(String afterVal) {
        this.afterVal = afterVal;
    }

    /**
     * Mengganti jenis record.
     *
     * @param logType jenis baru, tidak boleh null
     * @throws NullPointerException jika {@code logType} null
     */
    public void setLogType(LogType logType) {
        this.logType = Objects.requireNonNull(logType, "logType");
    }

    /** Mencetak record ini dalam satu baris ke standard output. */
    public void printWALRecord() {
        System.out.println(this);
    }

    /**
     * Mengubah record ini menjadi byte. {@link #deserialize} membacanya kembali.
     *
     * @return byte hasil encoding, tidak pernah null
     */
    public byte[] serialize() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(lsn);
            out.writeInt(idTransaction);
            out.writeUTF(logType.name());
            out.writeLong(timestamp);
            writeNullable(out, tableName);
            out.writeInt(idRecord);
            writeNullable(out, beforeVal);
            writeNullable(out, afterVal);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Membaca kembali record yang ditulis oleh {@link #serialize}.
     *
     * @param data byte record, tidak boleh null
     * @return record hasil decoding
     * @throws IllegalArgumentException jika byte tersebut bukan satu record utuh
     */
    public static WALRecord deserialize(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return new WALRecord(
                    in.readInt(),
                    in.readInt(),
                    LogType.valueOf(in.readUTF()),
                    in.readLong(),
                    readNullable(in),
                    in.readInt(),
                    readNullable(in),
                    readNullable(in));
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a whole WAL record", e);
        }
    }

    /** Menulis penanda ada/tidaknya string, lalu isinya jika tidak null. */
    private static void writeNullable(DataOutputStream out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) {
            out.writeUTF(s);
        }
    }

    /** Kebalikan dari {@link #writeNullable}. */
    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    /** {@inheritDoc} Format: {@code [LSN 3] T1 UPDATE account#5 {lama} -> {baru}}. */
    @Override
    public String toString() {
        String head = "[LSN " + lsn + "] T" + idTransaction + " " + logType;
        if (!logType.isDataChange()) {
            return head;
        }
        return head + " " + tableName + "#" + idRecord + " " + beforeVal + " -> " + afterVal;
    }
}
