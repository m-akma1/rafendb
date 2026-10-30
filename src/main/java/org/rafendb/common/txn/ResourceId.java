package org.rafendb.common.txn;

/**
 * Identity of the data a transaction accesses; the key for locks and timestamps. Either a whole
 * table or one row, identified by the RID {@code <pageId, slotId>} from the Storage Manager.
 *
 * @param table the table name, not blank
 * @param pageId the RID page id, or {@link #NO_ROW} for a table-level resource
 * @param slotId the RID slot id, or {@link #NO_ROW} for a table-level resource
 */
public record ResourceId(String table, int pageId, int slotId) {
    /** Value of {@code pageId} and {@code slotId} for a table-level resource. */
    public static final int NO_ROW = -1;

    /**
     * @throws IllegalArgumentException if {@code table} is null or blank, or if only one of {@code
     *     pageId} and {@code slotId} is {@link #NO_ROW}
     */
    public ResourceId {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
        if ((pageId == NO_ROW) != (slotId == NO_ROW)) {
            throw new IllegalArgumentException("pageId and slotId must both be set or both unset");
        }
    }

    /** Returns a resource for the whole table. */
    public static ResourceId ofTable(String table) {
        return new ResourceId(table, NO_ROW, NO_ROW);
    }

    /** Returns a resource for one row. */
    public static ResourceId ofRow(String table, int pageId, int slotId) {
        return new ResourceId(table, pageId, slotId);
    }

    /** Returns {@code true} if this stands for a whole table. */
    public boolean isTable() {
        return pageId == NO_ROW;
    }
}
