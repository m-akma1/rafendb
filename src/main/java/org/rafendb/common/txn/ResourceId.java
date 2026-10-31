package org.rafendb.common.txn;

import java.util.Objects;
import org.rafendb.common.tuple.Rid;

/**
 * Identity of the data a transaction accesses; the key for locks and timestamps. Either a whole
 * table or one row. A {@link Rid} has no table name, so a row is identified by the table together
 * with its Rid.
 *
 * @param table the table name, not blank
 * @param rid the row address from the Storage Manager, or {@code null} for a table-level resource
 */
public record ResourceId(String table, Rid rid) {
    /** @throws IllegalArgumentException if {@code table} is null or blank */
    public ResourceId {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table must not be blank");
        }
    }

    /** Returns a resource for the whole table. */
    public static ResourceId ofTable(String table) {
        return new ResourceId(table, null);
    }

    /**
     * Returns a resource for one row.
     *
     * @throws NullPointerException if {@code rid} is null
     */
    public static ResourceId ofRow(String table, Rid rid) {
        return new ResourceId(table, Objects.requireNonNull(rid, "rid"));
    }

    /** Returns {@code true} if this stands for a whole table. */
    public boolean isTable() {
        return rid == null;
    }
}