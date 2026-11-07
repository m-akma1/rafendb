package org.rafendb.mock.storage;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;
import org.rafendb.api.storage.RecordCursor;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.IndexInfo;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.schema.ReferencingForeignKey;
import org.rafendb.common.schema.ReferentialAction;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.schema.TableStats;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.StoredRow;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * Mock {@link StorageManager} that returns dummy data. It serves two
 * hard-coded, immutable tables
 * ({@code prodi} and {@code mahasiswa}) and two dummy indexes, validates its
 * arguments as the
 * interface contract requires, and never changes the dummy data: writes are
 * accepted but have no
 * effect. Its only state is a closed flag. Thread-safe.
 */
public final class MockStorageManager implements StorageManager {
    private static final int BLOCK_SIZE = 4096;
    private static final int NUMERIC_SIZE = 4;

    private static final ForeignKey MAHASISWA_PRODI_FK =
            new ForeignKey(
                    "mahasiswa_prodi_fk",
                    List.of("kode_prodi"),
                    "prodi",
                    List.of("kode"),
                    ReferentialAction.RESTRICT);

    private static final Table PRODI =
            table(
                    "prodi",
                    List.of(
                            new Column("kode", DataType.CHAR, 3, false, true),
                            new Column("nama", DataType.VARCHAR, 50, false, false)),
                    List.of(),
                    List.of(
                            prodiRow("IF", "Teknik Informatika"),
                            prodiRow("STI", "Sistem dan Teknologi Informasi"),
                            prodiRow("EL", "Teknik Elektro")));

    private static final Table MAHASISWA =
            table(
                    "mahasiswa",
                    List.of(
                            new Column("nim", DataType.INTEGER, 0, false, true),
                            new Column("nama", DataType.VARCHAR, 50, false, false),
                            new Column("ipk", DataType.FLOAT, 0, true, false),
                            new Column("kode_prodi", DataType.CHAR, 3, false, false)),
                    List.of(MAHASISWA_PRODI_FK),
                    List.of(
                            mahasiswaRow(13524275, "John Doe", 3.75f, "IF"),
                            mahasiswaRow(13524276, "Jane Smith", 3.92f, "IF"),
                            mahasiswaRow(18224278, "Michael Johnson", null, "STI"),
                            mahasiswaRow(13224279, "Sarah Williams", 3.40f, "EL"),
                            mahasiswaRow(18224280, "David Brown", 3.10f, "STI")));

    private static final Map<String, Table> TABLES =
            Map.of(MAHASISWA.schema().table(), MAHASISWA, PRODI.schema().table(), PRODI);

    private static final List<IndexInfo> INDEXES =
            List.of(
                    new IndexInfo("mahasiswa_nim_idx", "mahasiswa", "nim", IndexType.B_PLUS_TREE),
                    new IndexInfo("prodi_kode_idx", "prodi", "kode", IndexType.HASH));

    private volatile boolean closed;

    /** Creates a mock Storage Manager that returns dummy data. */
    public MockStorageManager() {}

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns {@code ["mahasiswa", "prodi"]}.
     */
    @Override
    public List<String> listTables() {
        ensureOpen();
        return List.of(MAHASISWA.schema().table(), PRODI.schema().table());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns the hard-coded schema of a dummy table.
     */
    @Override
    public TableSchema getSchema(String table) {
        ensureOpen();
        return tableNamed(table).schema();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns the dummy foreign keys that reference the table, possibly none.
     */
    @Override
    public List<ReferencingForeignKey> listReferencingForeignKeys(String table) {
        ensureOpen();
        return referencingKeys(tableNamed(table));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: throws if the name is a dummy table; otherwise does nothing.
     */
    @Override
    public void createTable(TableSchema schema) throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(schema, "schema");
        if (TABLES.containsKey(schema.table())) {
            throw new StorageRuntimeException("table already exists: " + schema.table());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: throws if another dummy table references the table and {@code cascade} is
     * {@code false}; nothing is dropped.
     */
    @Override
    public void dropTable(String table, boolean cascade) throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        if (!cascade
                && referencingKeys(t).stream().anyMatch(k -> !k.referencingTable().equals(table))) {
            throw new StorageRuntimeException("table is referenced by a foreign key: " + table);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns statistics computed from the dummy rows, with one block.
     */
    @Override
    public TableStats getStats(String table) throws StorageInternalException {
        ensureOpen();
        return statsOf(tableNamed(table));
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns the dummy indexes of the table, possibly none.
     */
    @Override
    public List<IndexInfo> listIndexes(String table) {
        ensureOpen();
        Table t = tableNamed(table);
        return INDEXES.stream().filter(i -> i.table().equals(t.schema().table())).toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns a cursor over the dummy rows in Rid order.
     */
    @Override
    public RecordCursor scan(String table) throws StorageInternalException {
        ensureOpen();
        return new MockRecordCursor(tableNamed(table).rows());
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns the dummy row at that Rid, or empty if there is none.
     */
    @Override
    public Optional<StoredRow> fetch(String table, Rid rid) throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        Objects.requireNonNull(rid, "rid");
        return t.rows().stream().filter(r -> r.rid().equals(rid)).findFirst();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: validates the row and returns the Rid after the last dummy row; nothing
     * is reserved.
     */
    @Override
    public Rid reserveRid(String table, Row row) throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        Objects.requireNonNull(row, "row");
        validateRow(t, row);
        return t.nextRid();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: does nothing.
     */
    @Override
    public void cancelReservation(String table, Rid rid) throws StorageInternalException {
        ensureOpen();
        tableNamed(table);
        Objects.requireNonNull(rid, "rid");
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: validates the row and returns {@code rid}; nothing is stored.
     */
    @Override
    public Rid insert(String table, Rid rid, Row row, long lsn) throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(row, "row");
        String mismatch = rowMismatch(t, row);
        if (mismatch != null) {
            throw new StorageInternalException(mismatch);
        }
        return rid;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: validates the row and that {@code rid} is a dummy Rid; nothing is
     * changed.
     */
    @Override
    public void update(String table, Rid rid, Row row, long lsn) throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(row, "row");
        validateRow(t, row);
        if (!t.hasRid(rid)) {
            throw new StorageInternalException("no live record at " + rid + " in table " + table);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns whether {@code rid} is a dummy Rid; nothing is deleted.
     */
    @Override
    public boolean delete(String table, Rid rid, long lsn) throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        Objects.requireNonNull(rid, "rid");
        return t.hasRid(rid);
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: calls {@code logDelete} once per dummy row in Rid order and returns the
     * row count;
     * nothing is deleted.
     */
    @Override
    public long clearTable(String table, ToLongFunction<StoredRow> logDelete)
            throws StorageInternalException {
        ensureOpen();
        Table t = tableNamed(table);
        Objects.requireNonNull(logDelete, "logDelete");
        for (StoredRow row : t.rows()) {
            logDelete.applyAsLong(row);
        }
        return t.rows().size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: validates the arguments only and does not record the index, so
     * {@code listIndexes} is unchanged afterwards.
     */
    @Override
    public void createIndex(IndexInfo index) throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(index, "index");
        Table t = tableNamed(index.table());
        if (t.schema().columnIndex(index.column()) < 0) {
            throw new StorageRuntimeException(
                    "unknown column: " + index.column() + " in table " + index.table());
        }
        if (INDEXES.stream().anyMatch(i -> i.name().equals(index.name()))) {
            throw new StorageRuntimeException("index name already used: " + index.name());
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns the Rids of the dummy rows whose indexed column equals
     * {@code key}.
     */
    @Override
    public List<Rid> lookupIndex(String index, Value key) throws StorageInternalException {
        ensureOpen();
        IndexInfo info = indexNamed(index);
        Objects.requireNonNull(key, "key");
        Table t = TABLES.get(info.table());
        int col = t.schema().columnIndex(info.column());
        requireSameFamily(t.schema().columns().get(col), key);
        if (key.isNull()) {
            return List.of();
        }
        return t.rows().stream()
                .filter(r -> r.row().get(col).equals(key))
                .map(StoredRow::rid)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: returns the Rids of the dummy rows within the bounds, ordered by key
     * then Rid.
     */
    @Override
    public List<Rid> rangeIndex(
            String index, Value lower, boolean includeLower, Value upper, boolean includeUpper)
            throws StorageInternalException {
        ensureOpen();
        IndexInfo info = indexNamed(index);
        if (!info.type().supportsRange()) {
            throw new StorageInternalException("index does not support range search: " + index);
        }
        Table t = TABLES.get(info.table());
        int col = t.schema().columnIndex(info.column());
        Column column = t.schema().columns().get(col);
        if (lower != null) {
            requireSameFamily(column, lower);
        }
        if (upper != null) {
            requireSameFamily(column, upper);
        }
        if ((lower != null && lower.isNull()) || (upper != null && upper.isNull())) {
            return List.of();
        }
        return t.rows().stream()
                .filter(r -> !r.row().get(col).isNull())
                .filter(
                        r ->
                                withinBounds(
                                        r.row().get(col), lower, includeLower, upper, includeUpper))
                .sorted(
                        Comparator.comparing((StoredRow r) -> r.row().get(col))
                                .thenComparing(StoredRow::rid))
                .map(StoredRow::rid)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: does nothing.
     */
    @Override
    public void flush() throws StorageInternalException {
        ensureOpen();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: validates the arguments and the image; nothing is changed.
     */
    @Override
    public void applyRecovery(String table, Rid rid, Optional<Row> image, long lsn)
            throws StorageInternalException {
        ensureOpen();
        Objects.requireNonNull(table, "table");
        Table t = TABLES.get(table);
        if (t == null) {
            throw new StorageInternalException("unknown table: " + table);
        }
        Objects.requireNonNull(rid, "rid");
        Objects.requireNonNull(image, "image");
        if (image.isPresent()) {
            String mismatch = rowMismatch(t, image.get());
            if (mismatch != null) {
                throw new StorageInternalException(mismatch);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: does nothing.
     */
    @Override
    public void reloadAfterRestore() throws StorageInternalException {
        ensureOpen();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: does nothing, the dummy tables are always present.
     */
    @Override
    public void loadSampleDatabase() throws StorageInternalException {
        ensureOpen();
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * Mock: marks this mock as closed; later calls to other methods throw {@link
     * IllegalStateException}.
     */
    @Override
    public void close() throws StorageInternalException {
        closed = true;
    }

    /**
     * Rejects use after {@link #close()}.
     *
     * @throws IllegalStateException if closed
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("storage manager is closed");
        }
    }

    /**
     * Finds a dummy table.
     *
     * @param name the table name
     * @return the table
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws StorageRuntimeException     if there is no such table
     */
    private static Table tableNamed(String name) {
        Objects.requireNonNull(name, "table");
        Table t = TABLES.get(name);
        if (t == null) {
            throw new StorageRuntimeException("unknown table: " + name);
        }
        return t;
    }

    /**
     * Collects the dummy foreign keys that reference a table, owners in listing order.
     *
     * @param t the referenced table
     * @return the foreign keys with their owning tables; unmodifiable
     */
    private static List<ReferencingForeignKey> referencingKeys(Table t) {
        return List.of(MAHASISWA, PRODI).stream()
                .flatMap(
                        owner ->
                                owner.schema().foreignKeys().stream()
                                        .filter(
                                                fk ->
                                                        fk.referencedTable()
                                                                .equals(t.schema().table()))
                                        .map(
                                                fk ->
                                                        new ReferencingForeignKey(
                                                                owner.schema().table(), fk)))
                .toList();
    }

    /**
     * Finds a dummy index.
     *
     * @param name the index name
     * @return the index definition
     * @throws NullPointerException if {@code name} is {@code null}
     * @throws StorageInternalException if there is no such index
     */
    private static IndexInfo indexNamed(String name) throws StorageInternalException {
        Objects.requireNonNull(name, "index");
        return INDEXES.stream()
                .filter(i -> i.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new StorageInternalException("unknown index: " + name));
    }

    /**
     * Checks a row from a client statement against the table's schema.
     *
     * @param t   the table
     * @param row the row
     * @throws StorageRuntimeException if the row does not match the schema
     */
    private static void validateRow(Table t, Row row) {
        String mismatch = rowMismatch(t, row);
        if (mismatch != null) {
            throw new StorageRuntimeException(mismatch);
        }
    }

    /**
     * Checks that a row has the table's arity and that every value fits its column.
     *
     * @param t   the table
     * @param row the row
     * @return why the row does not match the schema, or {@code null} if it matches
     */
    private static String rowMismatch(Table t, Row row) {
        List<Column> columns = t.schema().columns();
        if (row.size() != columns.size()) {
            return "row has " + row.size() + " values, expected " + columns.size();
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!columns.get(i).accepts(row.get(i))) {
                return "value " + row.get(i) + " does not fit column " + columns.get(i).name();
            }
        }
        return null;
    }

    /**
     * Checks that a key is in the same type family as a column: INTEGER, FLOAT, or
     * text.
     *
     * @param column the indexed column
     * @param key    the key or bound
     * @throws StorageInternalException if the families differ
     */
    private static void requireSameFamily(Column column, Value key)
            throws StorageInternalException {
        DataType a = column.type();
        DataType b = key.type();
        if (a != b && !(a.isText() && b.isText())) {
            throw new StorageInternalException(
                    "key of type "
                            + b
                            + " does not match column "
                            + column.name()
                            + " of type "
                            + a);
        }
    }

    /**
     * Tests a non-NULL value against range bounds.
     *
     * @param v            the value
     * @param lower        the lower bound, or {@code null} if unbounded
     * @param includeLower whether {@code lower} matches
     * @param upper        the upper bound, or {@code null} if unbounded
     * @param includeUpper whether {@code upper} matches
     * @return {@code true} if within the bounds
     */
    private static boolean withinBounds(
            Value v, Value lower, boolean includeLower, Value upper, boolean includeUpper) {
        if (lower != null) {
            int c = v.compareTo(lower);
            if (c < 0 || (c == 0 && !includeLower)) {
                return false;
            }
        }
        if (upper != null) {
            int c = v.compareTo(upper);
            return c < 0 || (c == 0 && includeUpper);
        }
        return true;
    }

    /**
     * Computes statistics from a dummy table: one block, NULLs not counted as
     * distinct values.
     *
     * @param t the table
     * @return the statistics
     */
    private static TableStats statsOf(Table t) {
        List<Column> columns = t.schema().columns();
        long totalSize = 0;
        for (StoredRow r : t.rows()) {
            for (Value v : r.row().values()) {
                totalSize += sizeOf(v);
            }
        }
        double averageSize = t.rows().isEmpty() ? 0 : (double) totalSize / t.rows().size();
        double blockingFactor = averageSize == 0 ? 0 : BLOCK_SIZE / averageSize;
        Map<String, Long> distinct = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            int col = i;
            distinct.put(
                    columns.get(col).name(),
                    t.rows().stream()
                            .map(r -> r.row().get(col))
                            .filter(v -> !v.isNull())
                            .distinct()
                            .count());
        }
        return new TableStats(t.rows().size(), 1, averageSize, blockingFactor, distinct);
    }

    /**
     * Returns the stored size of a value in bytes.
     *
     * @param v the value
     * @return the size, zero for NULL
     */
    private static int sizeOf(Value v) {
        if (v.isNull()) {
            return 0;
        }
        return v.type().isText()
                ? v.asText().getBytes(StandardCharsets.UTF_8).length
                : NUMERIC_SIZE;
    }

    /**
     * Builds a dummy table whose rows get Rids in block zero with slot equal to
     * position.
     *
     * @param name    the table name
     * @param columns the columns
     * @param foreignKeys the outgoing foreign keys
     * @param rows    the row values in order
     * @return the table
     */
    private static Table table(
            String name, List<Column> columns, List<ForeignKey> foreignKeys, List<Row> rows) {
        List<StoredRow> stored =
                IntStream.range(0, rows.size())
                        .mapToObj(i -> new StoredRow(ridAt(i), rows.get(i)))
                        .toList();
        return new Table(new TableSchema(name, columns, foreignKeys), stored);
    }

    /**
     * Builds the Rid of a dummy slot.
     *
     * @param slot the slot number
     * @return the Rid, in block zero
     */
    private static Rid ridAt(int slot) {
        return new Rid(0, slot);
    }

    /**
     * Builds a {@code prodi} row.
     *
     * @param kode the program code
     * @param nama the program name
     * @return the row
     */
    private static Row prodiRow(String kode, String nama) {
        return new Row(
                List.of(Value.ofText(DataType.CHAR, kode), Value.ofText(DataType.VARCHAR, nama)));
    }

    /**
     * Builds a {@code mahasiswa} row.
     *
     * @param nim       the student number
     * @param nama      the student name
     * @param ipk       the GPA, or {@code null} for SQL NULL
     * @param kodeProdi the program code
     * @return the row
     */
    private static Row mahasiswaRow(int nim, String nama, Float ipk, String kodeProdi) {
        return new Row(
                List.of(
                        Value.ofInt(nim),
                        Value.ofText(DataType.VARCHAR, nama),
                        ipk == null ? Value.nullOf(DataType.FLOAT) : Value.ofFloat(ipk),
                        Value.ofText(DataType.CHAR, kodeProdi)));
    }

    /**
     * A dummy table.
     *
     * @param schema the schema
     * @param rows   the rows in Rid order
     */
    private record Table(TableSchema schema, List<StoredRow> rows) {
        /**
         * Returns whether a dummy row has this Rid.
         *
         * @param rid the Rid
         * @return {@code true} if present
         */
        boolean hasRid(Rid rid) {
            return rows.stream().anyMatch(r -> r.rid().equals(rid));
        }

        /**
         * Returns the Rid after the last dummy row.
         *
         * @return the Rid
         */
        Rid nextRid() {
            return ridAt(rows.size());
        }
    }
}
