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
    private static final int FORMAT_MAGIC_V2 = 0x57414C32; // "WAL2"

    private final long lsn;
    private final int idTransaction;
    private LogType logType;
    private final long timestamp;
    private final String tableName;
    private final long tableGeneration;
    private final int idRecord;
    private final Rid rid;
    private final String beforeVal;
    private String afterVal;
    private long checksum;

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
        this(lsn, idTransaction, logType, timestamp, tableName, 0L, idRecord, beforeVal, afterVal);
    }

    /**
     * Membuat record dengan idRecord numerik dan generasi tabel.
     *
     * @param lsn nomor urut log (LSN), unik dan terus naik
     * @param idTransaction transaksi yang menulis record ini
     * @param logType jenis record, tidak boleh null
     * @param timestamp waktu record ditulis, dalam milidetik epoch
     * @param tableName tabel yang diubah, atau {@code null} jika bukan perubahan data
     * @param tableGeneration generasi persisten tabel saat record ditulis
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
            long tableGeneration,
            int idRecord,
            String beforeVal,
            String afterVal) {
        this.lsn = lsn;
        this.idTransaction = idTransaction;
        this.logType = Objects.requireNonNull(logType, "logType");
        this.timestamp = timestamp;
        this.tableName = tableName;
        this.tableGeneration = tableGeneration;
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
        this(lsn, idTransaction, logType, timestamp, tableName, 0L, rid, beforeVal, afterVal);
    }

    /**
     * Membuat record dengan {@link Rid} dan generasi tabel.
     *
     * @param lsn nomor urut log (LSN)
     * @param idTransaction id transaksi
     * @param logType tipe record log
     * @param timestamp waktu penulisan log
     * @param tableName nama tabel
     * @param tableGeneration generasi persisten tabel saat record ditulis
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
            long tableGeneration,
            Rid rid,
            String beforeVal,
            String afterVal) {
        this.lsn = lsn;
        this.idTransaction = idTransaction;
        this.logType = Objects.requireNonNull(logType, "logType");
        this.timestamp = timestamp;
        this.tableName = tableName;
        this.tableGeneration = tableGeneration;
        this.rid = rid;
        this.idRecord = rid != null ? (int) (rid.block() * 1000 + rid.slot()) : -1;
        this.beforeVal = beforeVal;
        this.afterVal = afterVal;
        this.checksum = calculateChecksum();
    }

    private long calculateChecksum() {
        CRC32 crc = new CRC32();
        updateLong(crc, lsn);
        updateInt(crc, idTransaction);
        updateInt(crc, logType.ordinal());
        updateLong(crc, timestamp);
        if (tableName != null) {
            byte[] nameBytes = tableName.getBytes(StandardCharsets.UTF_8);
            updateInt(crc, nameBytes.length);
            crc.update(nameBytes);
        } else {
            updateInt(crc, -1);
        }
        updateLong(crc, tableGeneration);
        updateInt(crc, idRecord);
        if (rid != null) {
            crc.update(1);
            updateLong(crc, rid.block());
            updateInt(crc, rid.slot());
        } else {
            crc.update(0);
        }
        if (beforeVal != null) {
            byte[] beforeBytes = beforeVal.getBytes(StandardCharsets.UTF_8);
            updateInt(crc, beforeBytes.length);
            crc.update(beforeBytes);
        } else {
            updateInt(crc, -1);
        }
        if (afterVal != null) {
            byte[] afterBytes = afterVal.getBytes(StandardCharsets.UTF_8);
            updateInt(crc, afterBytes.length);
            crc.update(afterBytes);
        } else {
            updateInt(crc, -1);
        }
        return crc.getValue();
    }

    private static void updateInt(CRC32 crc, int v) {
        crc.update((v >>> 24) & 0xFF);
        crc.update((v >>> 16) & 0xFF);
        crc.update((v >>> 8) & 0xFF);
        crc.update(v & 0xFF);
    }

    private static void updateLong(CRC32 crc, long v) {
        updateInt(crc, (int) (v >>> 32));
        updateInt(crc, (int) v);
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

    /** @return generasi persisten tabel saat record ditulis */
    public long getTableGeneration() {
        return tableGeneration;
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
        this.checksum = calculateChecksum();
    }

    /**
     * Mengganti jenis record.
     *
     * @param logType jenis baru, tidak boleh null
     * @throws NullPointerException jika {@code logType} null
     */
    public void setLogType(LogType logType) {
        this.logType = Objects.requireNonNull(logType, "logType");
        this.checksum = calculateChecksum();
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
     * Mengubah record ini menjadi byte (format v2 dengan generasi tabel, Rid penuh, dan CRC32).
     * {@link #deserialize} membacanya kembali.
     *
     * @return byte hasil encoding, tidak pernah null
     */
    public byte[] serialize() {
        long currentChecksum = calculateChecksum();
        this.checksum = currentChecksum;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(FORMAT_MAGIC_V2);
            out.writeLong(lsn);
            out.writeInt(idTransaction);
            out.writeUTF(logType.name());
            out.writeLong(timestamp);
            writeNullableUtf8(out, tableName);
            out.writeLong(tableGeneration);
            out.writeInt(idRecord);
            out.writeBoolean(rid != null);
            if (rid != null) {
                out.writeLong(rid.block());
                out.writeInt(rid.slot());
            }
            writeNullableUtf8(out, beforeVal);
            writeNullableUtf8(out, afterVal);
            out.writeLong(currentChecksum);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Membaca kembali record yang ditulis oleh {@link #serialize}. Mendukung format v2 (dengan
     * verifikasi checksum CRC32) dan format v1 lama.
     *
     * @param data byte record, tidak boleh null
     * @return record hasil decoding
     * @throws IllegalArgumentException jika byte tersebut bukan satu record utuh atau checksum
     *     tidak cocok
     */
    public static WALRecord deserialize(byte[] data) {
        Objects.requireNonNull(data, "data");
        if (data.length < Integer.BYTES) {
            throw new IllegalArgumentException("Not a whole WAL record");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int header = in.readInt();
            if (header == FORMAT_MAGIC_V2) {
                long lsn = in.readLong();
                int txId = in.readInt();
                LogType type = LogType.valueOf(in.readUTF());
                long ts = in.readLong();
                String table = readNullableUtf8(in);
                long generation = in.readLong();
                int legacyIdRecord = in.readInt();
                boolean hasRid = in.readBoolean();
                Rid rid = hasRid ? new Rid(in.readLong(), in.readInt()) : null;
                String before = readNullableUtf8(in);
                String after = readNullableUtf8(in);
                long storedChecksum = in.readLong();
                if (in.available() != 0) {
                    throw new IllegalArgumentException("Trailing bytes in WAL record");
                }
                WALRecord record =
                        hasRid
                                ? new WALRecord(
                                        lsn, txId, type, ts, table, generation, rid, before, after)
                                : new WALRecord(
                                        lsn,
                                        txId,
                                        type,
                                        ts,
                                        table,
                                        generation,
                                        legacyIdRecord,
                                        before,
                                        after);
                if (record.getChecksum() != storedChecksum) {
                    throw new IllegalArgumentException("WAL record checksum mismatch");
                }
                return record;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a whole WAL record", e);
        }

        // Legacy v1 fallback
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            WALRecord record =
                    new WALRecord(
                            in.readLong(),
                            in.readInt(),
                            LogType.valueOf(in.readUTF()),
                            in.readLong(),
                            readNullable(in),
                            in.readInt(),
                            readNullable(in),
                            readNullable(in));
            if (in.available() != 0) {
                throw new IllegalArgumentException("Trailing bytes in legacy WAL record");
            }
            return record;
        } catch (IOException e) {
            throw new IllegalArgumentException("Not a whole WAL record", e);
        }
    }

    /** Menulis string UTF-8 dengan panjang 32-bit agar tidak terbatas 64 KB seperti writeUTF. */
    private static void writeNullableUtf8(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
            return;
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    /** Kebalikan dari {@link #writeNullableUtf8}. */
    private static String readNullableUtf8(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len == -1) {
            return null;
        }
        if (len < 0 || len > 16 * 1024 * 1024) {
            throw new IOException("Invalid UTF-8 field length: " + len);
        }
        byte[] utf8 = in.readNBytes(len);
        if (utf8.length < len) {
            throw new IOException("Truncated UTF-8 field");
        }
        return new String(utf8, StandardCharsets.UTF_8);
    }

    /** Kebalikan dari {@link #writeNullable} untuk format v1 lama. */
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
