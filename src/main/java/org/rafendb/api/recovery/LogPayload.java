package org.rafendb.api.recovery;

import java.util.Objects;
import java.util.Optional;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.recovery.log.LogType;

/**
 * Immutable payload representing a WAL log entry to be written.
 */
public record LogPayload(
        int idTransaction,
        LogType opType,
        long timestamp,
        String tableName,
        int idRecord,
        String beforeVal,
        String afterVal) {

    public LogPayload {
        Objects.requireNonNull(opType, "opType must not be null");
    }

    public static LogPayload ofDataChange(
            int idTransaction,
            LogType opType,
            String tableName,
            int idRecord,
            String beforeVal,
            String afterVal) {
        return new LogPayload(
                idTransaction,
                opType,
                System.currentTimeMillis(),
                tableName,
                idRecord,
                beforeVal,
                afterVal);
    }

    public static LogPayload ofTransactionEvent(int idTransaction, LogType opType) {
        return new LogPayload(
                idTransaction, opType, System.currentTimeMillis(), null, -1, null, null);
    }

    /**
     * Membuat payload perubahan data langsung menggunakan representasi data standar RafenDB
     * ({@link Rid} dan {@link Row}).
     */
    public static LogPayload ofDataChange(
            int idTransaction,
            LogType opType,
            String tableName,
            Rid rid,
            Optional<Row> beforeImage,
            Optional<Row> afterImage) {
        int recordId = (rid == null) ? -1 : (int) (rid.block() * 1000 + rid.slot());
        String beforeStr =
                beforeImage != null && beforeImage.isPresent()
                        ? beforeImage.get().toString()
                        : null;
        String afterStr =
                afterImage != null && afterImage.isPresent() ? afterImage.get().toString() : null;
        return new LogPayload(
                idTransaction,
                opType,
                System.currentTimeMillis(),
                tableName,
                recordId,
                beforeStr,
                afterStr);
    }
}
