package org.rafendb.storage.seed;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.ReferentialAction;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * Loads a small relational sample database through the StorageManager facade.
 *
 * <p>The caller must provide exclusive bootstrap access. The database is left untouched when
 * it already contains tables.
 */
public class SampleDatabaseLoader {

    private static final int ROWS_PER_TABLE = 60;

    /**
     * Populates an empty database with three related tables and sample rows covering all four
     * SQL types (INTEGER, FLOAT, VARCHAR, CHAR).
     *
     * <p>LSN NOTE: {@link StorageManager#insert} requires the LSN of an already-created log
     * record. This implementation assumes bootstrap reserves the LSN range beginning at 1 for
     * seed inserts, with no possibility of collision with normal WAL records. If the WAL does
     * not provide that convention, the facade must supply an LSN allocator/logger instead.
     */
    public void load(StorageManager storage) throws StorageInternalException {
        if (storage == null) {
            throw new NullPointerException("storage");
        }
        if (!storage.listTables().isEmpty()) {
            return;
        }

        // Bootstrap-only sequence; see the LSN contract in the method Javadoc above.
        AtomicLong nextLsn = new AtomicLong(1L);

        TableSchema customers = customersSchema();
        TableSchema orders = ordersSchema();
        TableSchema orderItems = orderItemsSchema();

        storage.createTable(customers);
        storage.createTable(orders);
        storage.createTable(orderItems);

        // Parent table: customers. INTEGER, VARCHAR, FLOAT, CHAR.
        for (int i = 1; i <= ROWS_PER_TABLE; i++) {
            Row row =
                    new Row(
                            List.of(
                                    Value.ofInt(i), // INTEGER PK
                                    Value.ofText(DataType.VARCHAR, "Customer " + i), // VARCHAR
                                    Value.ofFloat(50.0f + i * 1.5f), // FLOAT
                                    Value.ofText(
                                            DataType.CHAR, i % 2 == 0 ? "A" : "I"))); // CHAR(1)
            insertRow(storage, "customers", row, nextLsn);
        }

        // Child table: each order references an existing customer.
        for (int i = 1; i <= ROWS_PER_TABLE; i++) {
            int customerId = ((i - 1) % ROWS_PER_TABLE) + 1;
            Row row =
                    new Row(
                            List.of(
                                    Value.ofInt(i), // INTEGER PK
                                    Value.ofInt(customerId), // INTEGER FK
                                    Value.ofFloat(10.0f + i), // FLOAT
                                    Value.ofText(DataType.VARCHAR, "Order-" + i))); // VARCHAR
            insertRow(storage, "orders", row, nextLsn);
        }

        // Grandchild table: each item references an existing order.
        for (int i = 1; i <= ROWS_PER_TABLE; i++) {
            int orderId = ((i - 1) % ROWS_PER_TABLE) + 1;
            Row row =
                    new Row(
                            List.of(
                                    Value.ofInt(i), // INTEGER PK
                                    Value.ofInt(orderId), // INTEGER FK
                                    Value.ofText(DataType.VARCHAR, "SKU-" + (1000 + i)), // VARCHAR
                                    Value.ofText(DataType.CHAR, i % 3 == 0 ? "Y" : "N"), // CHAR(1)
                                    Value.ofFloat(2.5f + i * 0.1f))); // FLOAT
            insertRow(storage, "order_items", row, nextLsn);
        }

        // Indexes are not implied by primary-key declarations in this storage API.
        storage.createIndex(
                new IndexInfo("idx_customers_name", "customers", "name", IndexType.HASH));
        storage.createIndex(
                new IndexInfo(
                        "idx_orders_customer_id", "orders", "customer_id", IndexType.B_PLUS_TREE));
        storage.createIndex(
                new IndexInfo(
                        "idx_order_items_order_id",
                        "order_items",
                        "order_id",
                        IndexType.B_PLUS_TREE));

        storage.flush();
    }

    // =====================================================================================
    // Schema definitions
    // =====================================================================================

    private static TableSchema customersSchema() {
        return new TableSchema(
                "customers",
                List.of(
                        // INTEGER: length 0.
                        new Column("customer_id", DataType.INTEGER, 0, false, true),
                        // VARCHAR: length in UTF-8 bytes, 1..4000.
                        new Column("name", DataType.VARCHAR, 100, false, false),
                        // FLOAT: length 0; nullable.
                        new Column("credit", DataType.FLOAT, 0, true, false),
                        // CHAR(1): fixed-width, length in UTF-8 bytes.
                        new Column("status", DataType.CHAR, 1, false, false)));
    }

    private static TableSchema ordersSchema() {
        return new TableSchema(
                "orders",
                List.of(
                        new Column("order_id", DataType.INTEGER, 0, false, true),
                        new Column("customer_id", DataType.INTEGER, 0, false, false),
                        new Column("amount", DataType.FLOAT, 0, false, false),
                        new Column("note", DataType.VARCHAR, 200, true, false)),
                List.of(
                        new ForeignKey(
                                "fk_orders_customer",
                                List.of("customer_id"),
                                "customers",
                                List.of("customer_id"),
                                ReferentialAction.RESTRICT)));
    }

    private static TableSchema orderItemsSchema() {
        return new TableSchema(
                "order_items",
                List.of(
                        new Column("item_id", DataType.INTEGER, 0, false, true),
                        new Column("order_id", DataType.INTEGER, 0, false, false),
                        new Column("sku", DataType.VARCHAR, 32, false, false),
                        new Column("gift", DataType.CHAR, 1, false, false),
                        new Column("price", DataType.FLOAT, 0, false, false)),
                List.of(
                        new ForeignKey(
                                "fk_items_order",
                                List.of("order_id"),
                                "orders",
                                List.of("order_id"),
                                ReferentialAction.RESTRICT)));
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    /**
     * Reserves a slot, inserts the row, and cancels the reservation if the insert fails.
     *
     * <p>The slot number is never handed out again by {@code reserveRid}, even after a
     * cancellation; the space may be reused but the Rid is not. So a failed seed leaves a
     * harmless hole in the new table's heap, never a collision.
     */
    private static Rid insertRow(StorageManager storage, String table, Row row, AtomicLong nextLsn)
            throws StorageInternalException {
        Rid rid = storage.reserveRid(table, row);
        boolean inserted = false;
        try {
            storage.insert(table, rid, row, nextLsn.getAndIncrement());
            inserted = true;
            return rid;
        } finally {
            if (!inserted) {
                try {
                    storage.cancelReservation(table, rid);
                } catch (StorageInternalException | RuntimeException ignored) {
                    // Best-effort cleanup; the original insertion exception propagates.
                }
            }
        }
    }

    /** Returns the names of the tables created by this loader. */
    public static List<String> seededTables() {
        return List.of("customers", "orders", "order_items");
    }

    /** Counts live rows in a table. */
    public long countRows(StorageManager storage, String table) throws StorageInternalException {
        long count = 0;
        try (RecordCursor cursor = storage.scan(table)) {
            while ((cursor.next()) != null) {
                count++;
            }
        }
        return count;
    }
}
