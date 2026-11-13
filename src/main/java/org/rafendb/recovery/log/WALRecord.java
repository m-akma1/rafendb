package org.rafendb.recovery.log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;
import org.rafendb.common.tuple.Rid;

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
    private final long lsn;
    private final int idTransaction;
    private LogType logType;
    private final long timestamp;
    private final String tableName;
    private final int idRecord;
    private final Rid rid;
    private final String beforeVal;
    private String afterVal;
    private final long checksum;

    /**
     * Membuat record dengan idRecord numerik (kompatibilitas pengujian).
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
            long lsn,
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
        this.rid = idRecord >= 0 ? new Rid((long) idRecord / 1000, idRecord % 1000) : null;
        this.beforeVal = beforeVal;
        this.afterVal = afterVal;
        this.checksum = calculateChecksum();
    }

    /**
     * Membuat record dengan {@link Rid} (representasi standar baris fisik RafenDB).
     *
     * @param lsn nomor urut log (LSN)
     * @param idTransaction id transaksi
     * @param logType tipe record log
     * @param timestamp waktu penulisan log
     * @param tableName nama tabel
     * @param rid alamat slot baris pada tabel
     * @param beforeVal isi sebelum perubahan
     * @param afterVal isi sesudah perubahan
     */
    public WALRecord(
            long lsn,
            int idTransaction,
            LogType logType,
            long timestamp,
            String tableName,
            Rid rid,
            String beforeVal,
            String afterVal) {
        this.lsn = lsn;
        this.idTransaction = idTransaction;
        this.logType = Objects.requireNonNull(logType, "logType");
        this.timestamp = timestamp;
        this.tableName = tableName;
        this.rid = rid;
        this.idRecord = rid != null ? (int) (rid.block() * 1000 + rid.slot()) : -1;
        this.beforeVal = beforeVal;
        this.afterVal = afterVal;
        this.checksum = calculateChecksum();
    }

    private long calculateChecksum() {
        CRC32 crc = new CRC32();
        crc.update((int) (lsn ^ (lsn >>> 32)));
        crc.update(idTransaction);
        crc.update(logType.ordinal());
        crc.update((int) (timestamp ^ (timestamp >>> 32)));
        if (tableName != null) {
            crc.update(tableName.getBytes(StandardCharsets.UTF_8));
        }
        crc.update(idRecord);
        if (beforeVal != null) {
            crc.update(beforeVal.getBytes(StandardCharsets.UTF_8));
        }
        if (afterVal != null) {
            crc.update(afterVal.getBytes(StandardCharsets.UTF_8));
        }
        return crc.getValue();
    }

    /** @return nomor urut log (LSN) */
    public long getLSN() {
        return lsn;
    }

    /**
     * Alias getter LSN sesuai konvensi camelCase.
     *
     * @return nomor urut log (LSN)
     */
    public long getLsn() {
        return lsn;
    }

    /** @return id transaksi yang menulis record ini */
    public int getIdTransaction() {
        return idTransaction;
    }

    /**
     * Alias getter untuk diagram kelas.
     *
     * @return id transaksi
     */
    public int getTransactionId() {
        return idTransaction;
    }

    /** @return jenis record, tidak pernah null */
    public LogType getLogType() {
        return logType;
    }

    /**
     * Alias getter tipe log.
     *
     * @return jenis record
     */
    public LogType getType() {
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

    /** @return alamat slot record (Rid) atau {@code null} jika bukan perubahan data */
    public Rid getRid() {
        return rid;
    }

    /** @return nilai checksum integritas record */
    public long getChecksum() {
        return checksum;
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
     * Alias getter untuk diagram kelas FRM.
     *
     * @return nilai sebelum
     */
    public String getBeforeImage() {
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
     * Alias getter untuk diagram kelas FRM.
     *
     * @return nilai sesudah
     */
    public String getAfterImage() {
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

    /** Alias printWALRecord untuk diagram kelas FRM. */
    public void printWalRecord() {
        printWALRecord();
    }

    /**
     * Mengubah record ini menjadi byte. {@link #deserialize} membacanya kembali.
     *
     * @return byte hasil encoding, tidak pernah null
     */
    public byte[] serialize() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeLong(lsn);
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
                    in.readLong(),
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
