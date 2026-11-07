package org.rafendb.mock.optimizer.catalog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.ReferentialAction;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.type.DataType;
import org.rafendb.optimizer.catalog.Catalog;

/** Immutable in-memory catalog for optimizer tests and mock integrations. */
public final class MockCatalog implements Catalog {
    private final Map<String, TableSchema> schemas;
    private final Map<String, TableStats> stats;
    private final Map<String, List<IndexInfo>> indexes;

    private MockCatalog(Builder builder) {
        schemas = Collections.unmodifiableMap(new LinkedHashMap<>(builder.schemas));
        stats = Collections.unmodifiableMap(new LinkedHashMap<>(builder.stats));
        Map<String, List<IndexInfo>> copied = new LinkedHashMap<>();
        builder.indexes.forEach((table, list) -> copied.put(table, List.copyOf(list)));
        indexes = Collections.unmodifiableMap(copied);
    }

    /** Returns the small mahasiswa/prodi fixture used by the storage mock. */
    public static MockCatalog defaults() {
        return builder()
                .table("mahasiswa")
                .columns(
                        new Column("nim", DataType.INTEGER, 0, false, true),
                        new Column("nama", DataType.VARCHAR, 50, false, false),
                        new Column("ipk", DataType.FLOAT, 0, true, false),
                        new Column("kode_prodi", DataType.CHAR, 3, false, false))
                .stats(
                        100,
                        1,
                        64,
                        64,
                        Map.of("nim", 100L, "nama", 100L, "ipk", 50L, "kode_prodi", 8L))
                .index(new IndexInfo("mahasiswa_nim_idx", "mahasiswa", "nim", IndexType.BTREE))
                .done()
                .table("prodi")
                .columns(
                        new Column("kode", DataType.CHAR, 3, false, true),
                        new Column("nama", DataType.VARCHAR, 50, false, false))
                .stats(8, 1, 57, 71, Map.of("kode", 8L, "nama", 8L))
                .index(new IndexInfo("prodi_kode_idx", "prodi", "kode", IndexType.HASH))
                .done()
                .build();
    }

    /** Returns a realistic three-table sample catalog. */
    public static MockCatalog samples() {
        return builder()
                .table("customers")
                .columns(
                        new Column("customer_id", DataType.INTEGER, 0, false, true),
                        new Column("name", DataType.VARCHAR, 100, false, false),
                        new Column("credit", DataType.FLOAT, 0, true, false),
                        new Column("status", DataType.CHAR, 1, false, false))
                .stats(
                        100,
                        4,
                        44,
                        93,
                        Map.of("customer_id", 100L, "name", 95L, "credit", 80L, "status", 4L))
                .index(new IndexInfo("idx_customers_name", "customers", "name", IndexType.HASH))
                .done()
                .table("orders")
                .columns(
                        new Column("order_id", DataType.INTEGER, 0, false, true),
                        new Column("customer_id", DataType.INTEGER, 0, false, false),
                        new Column("amount", DataType.FLOAT, 0, false, false),
                        new Column("note", DataType.VARCHAR, 200, true, false))
                .stats(
                        500,
                        17,
                        72,
                        56,
                        Map.of("order_id", 500L, "customer_id", 100L, "amount", 450L, "note", 120L))
                .foreignKey(
                        new ForeignKey(
                                "fk_orders_customer",
                                List.of("customer_id"),
                                "customers",
                                List.of("customer_id"),
                                ReferentialAction.RESTRICT))
                .index(
                        new IndexInfo(
                                "idx_orders_customer", "orders", "customer_id", IndexType.BTREE))
                .done()
                .table("order_items")
                .columns(
                        new Column("item_id", DataType.INTEGER, 0, false, true),
                        new Column("order_id", DataType.INTEGER, 0, false, false),
                        new Column("sku", DataType.VARCHAR, 32, false, false),
                        new Column("gift", DataType.CHAR, 1, false, false),
                        new Column("price", DataType.FLOAT, 0, false, false))
                .stats(
                        2000,
                        22,
                        44,
                        93,
                        Map.of(
                                "item_id",
                                2000L,
                                "order_id",
                                500L,
                                "sku",
                                800L,
                                "gift",
                                2L,
                                "price",
                                80L))
                .foreignKey(
                        new ForeignKey(
                                "fk_items_order",
                                List.of("order_id"),
                                "orders",
                                List.of("order_id"),
                                ReferentialAction.RESTRICT))
                .index(new IndexInfo("idx_items_order", "order_items", "order_id", IndexType.BTREE))
                .done()
                .build();
    }

    /** Starts an empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<String> listTables() {
        return List.copyOf(schemas.keySet());
    }

    @Override
    public TableSchema getSchema(String table) {
        TableSchema value = find(schemas, table);
        if (value == null) throw new StorageRuntimeException("Table does not exist: " + table);
        return value;
    }

    @Override
    public TableStats getStats(String table) {
        TableStats value = find(stats, table);
        if (value == null)
            throw new StorageRuntimeException("Statistics unavailable for table: " + table);
        return value;
    }

    @Override
    public List<IndexInfo> listIndexes(String table) {
        getSchema(table);
        return indexes.getOrDefault(canonical(table), List.of());
    }

    private String canonical(String table) {
        return schemas.keySet().stream()
                .filter(t -> t.equalsIgnoreCase(table))
                .findFirst()
                .orElse(table);
    }

    private static <T> T find(Map<String, T> map, String key) {
        T exact = map.get(key);
        return exact != null
                ? exact
                : map.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(key))
                        .map(Map.Entry::getValue)
                        .findFirst()
                        .orElse(null);
    }

    /** Mutable builder; finished catalogs are immutable. */
    public static final class Builder {
        private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
        private final Map<String, TableStats> stats = new LinkedHashMap<>();
        private final Map<String, List<IndexInfo>> indexes = new LinkedHashMap<>();
        private TableBuilder current;

        /** Starts a table definition. */
        public TableBuilder table(String name) {
            finishCurrent();
            current = new TableBuilder(this, name);
            return current;
        }

        /** Builds the immutable catalog. */
        public MockCatalog build() {
            finishCurrent();
            return new MockCatalog(this);
        }

        private void finishCurrent() {
            if (current != null) {
                current.commit();
                current = null;
            }
        }
    }

    /** Builder for one table's schema and metadata. */
    public static final class TableBuilder {
        private final Builder parent;
        private final String name;
        private final List<Column> columns = new ArrayList<>();
        private final List<ForeignKey> foreignKeys = new ArrayList<>();
        private final List<IndexInfo> indexes = new ArrayList<>();
        private TableStats stats;

        private TableBuilder(Builder parent, String name) {
            this.parent = parent;
            this.name = Objects.requireNonNull(name);
        }

        /** Sets columns in declaration order. */
        public TableBuilder columns(Column... columns) {
            this.columns.addAll(List.of(columns));
            return this;
        }

        /** Sets table statistics. */
        public TableBuilder stats(long n, long b, double l, double f, Map<String, Long> distinct) {
            stats = new TableStats(n, b, l, f, distinct);
            return this;
        }

        /** Adds an outgoing foreign-key constraint. */
        public TableBuilder foreignKey(ForeignKey foreignKey) {
            foreignKeys.add(Objects.requireNonNull(foreignKey, "foreignKey"));
            return this;
        }

        /** Adds an index. */
        public TableBuilder index(IndexInfo index) {
            indexes.add(index);
            return this;
        }

        /** Commits this table and returns the catalog builder. */
        public Builder done() {
            commit();
            parent.current = null;
            return parent;
        }

        private void commit() {
            if (columns.isEmpty() || stats == null)
                throw new IllegalStateException("columns and stats required for " + name);
            TableSchema schema = new TableSchema(name, columns, foreignKeys);
            for (IndexInfo index : indexes) {
                if (!index.table().equals(name))
                    throw new IllegalArgumentException(
                            "index " + index.name() + " belongs to table " + index.table());
                if (schema.column(index.column()).isEmpty())
                    throw new IllegalArgumentException(
                            "index " + index.name() + " names unknown column " + index.column());
            }
            parent.schemas.put(name, schema);
            parent.stats.put(name, stats);
            parent.indexes.put(name, List.copyOf(indexes));
        }
    }
}
