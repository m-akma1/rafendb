package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.query.CreateIndexQuery;
import org.rafendb.common.query.CreateTableQuery;
import org.rafendb.common.query.DeleteQuery;
import org.rafendb.common.query.DropTableQuery;
import org.rafendb.common.query.ExplainQuery;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.query.TransactionQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.optimizer.catalog.Catalog;
import org.rafendb.optimizer.catalog.StatementScopedCatalog;

/**
 * Binds parsed queries to canonical catalog names and validates their semantics.
 *
 * <p>This implementation is stateless after construction and is safe for concurrent use.
 */
public final class QueryBinder implements Binder {
    static final boolean ALLOW_INT_TO_FLOAT_WIDENING = true;
    private final Catalog catalog;

    /**
     * Creates a stateless binder using a non-null catalog.
     *
     * @param catalog metadata source, non-null and thread-safe
     * @throws NullPointerException if {@code catalog} is {@code null}
     */
    public QueryBinder(Catalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public Catalog catalog() {
        return catalog;
    }

    /**
     * Binds a parsed query deterministically; repeated binding returns an equal result.
     *
     * @param parsed parsed query, non-null
     * @return a non-null query with canonical names and validation applied
     * @throws NullPointerException if {@code parsed} is {@code null}
     * @throws ValidationException if names, types, values, or query structure are invalid
     */
    @Override
    public ParsedQuery bind(ParsedQuery parsed) {
        Objects.requireNonNull(parsed, "parsed");
        if(catalog instanceof StatementScopedCatalog scoped) {
            scoped.beginStatement();
        }
        if (parsed instanceof SelectQuery select) return bind(select);
        if (parsed instanceof ExplainQuery explain) return bindExplain(explain);
        if (parsed instanceof InsertQuery insert) return bindInsert(insert);
        if (parsed instanceof UpdateQuery update) return bindUpdate(update);
        if (parsed instanceof DeleteQuery delete) return bindDelete(delete);
        if (parsed instanceof CreateIndexQuery index) return bindIndex(index);
        if (parsed instanceof CreateTableQuery create) return bindCreateTable(create);
        if (parsed instanceof DropTableQuery drop) return bindDropTable(drop);
        if (parsed instanceof TransactionQuery) return parsed;
        throw new ValidationException("Unsupported query type: " + parsed.getClass().getName());
    }

    /**
     * Binds a SELECT.
     *
     * <p>The FROM tree keeps its shape except that every NATURAL JOIN becomes a {@code Join} with
     * the equality condition derived from the catalog (or a {@code CartesianProduct} when the two
     * sides share no column). {@code SELECT *} is expanded, dropping the right-hand copy of each
     * column a NATURAL JOIN merged.
     *
     * <p>Every {@code ColumnRef} gets its {@code offset}: the position of the column in the row
     * that the unoptimized plan feeds to the node holding the expression. For a join condition
     * that is the join's own input row (left tables then right tables); for WHERE, the projection
     * and ORDER BY it is the concatenation of all FROM tables in FROM order. A condition may only
     * name tables joined at or below its own join.
     *
     * @param query non-null SELECT query
     * @return the validated, canonical SELECT
     * @throws NullPointerException if {@code query} is {@code null}
     * @throws ValidationException if catalog names, expressions, or join types are invalid
     */
    public SelectQuery bind(SelectQuery query) {
        Objects.requireNonNull(query, "query");
        Scope all = new Scope(catalog, query.from().tableRefs());
        FromBinder fromBinder = new FromBinder(all);
        FromItem from = fromBinder.bind(query.from());
        Scope scope = all.withHidden(fromBinder.merged());
        ExprBinder binder = new ExprBinder(scope);
        Expr where = binder.condition(query.whereCondition(), "WHERE");
        List<Expr> boundProjection = new ArrayList<>();
        for (Expr expression : StarExpander.expand(query.projection(), scope))
            boundProjection.add(binder.bind(expression).expr());
        List<OrderByItem> orderBy = new ArrayList<>();
        for (OrderByItem item : query.orderBy()) {
            orderBy.add(new OrderByItem(binder.bind(item.expression()).expr(), item.ascending()));
        }
        return new SelectQuery(
                query.originalSql(),
                List.copyOf(boundProjection),
                from,
                where,
                List.copyOf(orderBy),
                query.limit());
    }

    private ExplainQuery bindExplain(ExplainQuery query) {
        SelectQuery select = bind(query.selectQuery());
        return new ExplainQuery(query.originalSql(), select);
    }

    private ParsedQuery bindInsert(InsertQuery query) {
        TableSchema schema = schema(query.table());
        if (query.values().size() != schema.columns().size())
            throw new ValidationException(
                    "INSERT into table '"
                            + schema.table()
                            + "' expects "
                            + schema.columns().size()
                            + " values, got "
                            + query.values().size());
        Scope scope = new Scope(schema);
        ExprBinder binder = new ExprBinder(scope);
        List<Expr> values = new ArrayList<>();
        for (int i = 0; i < query.values().size(); i++) {
            Expr expr = query.values().get(i);
            if (containsColumn(expr))
                throw new ValidationException(
                        "INSERT value for column '"
                                + schema.columns().get(i).name()
                                + "' cannot reference a column");
            Column target = schema.columns().get(i);
            values.add(binder.assign(expr, target, schema.table()));
        }
        return new InsertQuery(query.originalSql(), schema.table(), values);
    }

    private ParsedQuery bindUpdate(UpdateQuery query) {
        TableSchema schema = schema(query.table());
        Column target = findColumn(schema, query.column());
        Scope scope = new Scope(schema);
        ExprBinder binder = new ExprBinder(scope);
        Expr value = binder.assign(query.value(), target, schema.table());
        Expr where = binder.condition(query.where(), "WHERE");
        return new UpdateQuery(query.originalSql(), schema.table(), target.name(), value, where);
    }

    private ParsedQuery bindDelete(DeleteQuery query) {
        TableSchema schema = schema(query.table());
        Expr where = new ExprBinder(new Scope(schema)).condition(query.where(), "WHERE");
        return new DeleteQuery(query.originalSql(), schema.table(), where);
    }

    private ParsedQuery bindIndex(CreateIndexQuery query) {
        TableSchema schema = schema(query.table());
        Column column = findColumn(schema, query.column());
        return new CreateIndexQuery(
                query.originalSql(),
                query.indexName(),
                schema.table(),
                column.name(),
                query.indexType());
    }

    private TableSchema schema(String table) {
        try {
            String canonical = Scope.canonicalName(catalog.listTables(), table);
            if (canonical == null) throw new ValidationException("Table does not exist: " + table);
            return catalog.getSchema(canonical);
        } catch (StorageRuntimeException e) {
            throw new ValidationException("Table does not exist: " + table, e);
        }
    }

    private CreateTableQuery bindCreateTable(CreateTableQuery query) {
        String table = query.table();
        if (Scope.canonicalName(catalog.listTables(), table) != null)
            throw new ValidationException("Table already exists: " + table);
        if (query.columns().isEmpty())
            throw new ValidationException("Table requires at least one column: " + table);
        if (query.primaryKeys().size() > 1)
            throw new ValidationException("Multiple primary keys in table " + table);

        List<String> key =
                query.primaryKeys().isEmpty() ? List.of() : query.primaryKeys().getFirst();
        List<String> names = new ArrayList<>();
        for (var column : query.columns()) {
            if (Scope.canonicalName(names, column.name()) != null)
                throw new ValidationException("Duplicate column " + table + "." + column.name());
            names.add(column.name());
        }

        // Self-references resolve against these local columns before the table exists in storage.
        TableSchema local;
        try {
            local = new TableSchema(table, query.schemaColumns());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(
                    "Invalid definition of table " + table + ": " + e.getMessage(), e);
        }

        List<Column> primary = key.stream().map(name -> findColumn(local, name)).toList();
        if (!local.primaryKeyColumns().equals(primary))
            throw new ValidationException(
                    "PRIMARY KEY "
                            + key
                            + " in "
                            + table
                            + " must list distinct columns in declaration order");
        List<List<String>> primaryKeys =
                primary.isEmpty()
                        ? List.of()
                        : List.of(primary.stream().map(Column::name).toList());

        List<CreateTableQuery.Reference> references =
                query.foreignKeys().stream()
                        .map(reference -> bindForeignKey(local, reference))
                        .toList();
        return new CreateTableQuery(
                query.originalSql(), table, query.columns(), primaryKeys, references);
    }

    private CreateTableQuery.Reference bindForeignKey(
            TableSchema local, CreateTableQuery.Reference reference) {
        TableSchema target =
                Scope.eq(local.table(), reference.targetTable())
                        ? local
                        : schema(reference.targetTable());
        List<Column> targetKey = target.primaryKeyColumns();
        List<Column> childKey =
                reference.columns().stream().map(column -> findColumn(local, column)).toList();

        if (childKey.stream().distinct().count() != childKey.size())
            throw new ValidationException(
                    "Duplicate foreign key column in "
                            + local.table()
                            + ": "
                            + reference.columns());

        if (!reference.targetColumns().isEmpty()) {
            List<Column> requested =
                    reference.targetColumns().stream()
                            .map(column -> findColumn(target, column))
                            .toList();
            if (!requested.equals(targetKey))
                throw new ValidationException(
                        "Referenced columns "
                                + reference.targetColumns()
                                + " must match the primary key of "
                                + target.table()
                                + " in order");
        }
        if (childKey.size() != targetKey.size())
            throw new ValidationException(
                    "Foreign key "
                            + reference.columns()
                            + " in "
                            + local.table()
                            + " must match the primary key arity of "
                            + target.table());
        for (int i = 0; i < childKey.size(); i++) {
            Column child = childKey.get(i);
            Column parent = targetKey.get(i);
            if (child.type() != parent.type() && !(child.type().isText() && parent.type().isText()))
                throw new ValidationException(
                        "Incompatible foreign key types for "
                                + local.table()
                                + "."
                                + child.name()
                                + " and "
                                + target.table()
                                + "."
                                + parent.name());
        }

        return new CreateTableQuery.Reference(
                childKey.stream().map(Column::name).toList(),
                target.table(),
                targetKey.stream().map(Column::name).toList());
    }

    private DropTableQuery bindDropTable(DropTableQuery query) {
        String target = schema(query.table()).table();
        if (!query.cascade()) {
            for (String owner : catalog.listTables()) {
                if (Scope.eq(owner, target)) continue;
                for (ForeignKey key : catalog.getSchema(owner).foreignKeys()) {
                    if (Scope.eq(key.referencedTable(), target))
                        throw new ValidationException(
                                "Cannot drop "
                                        + target
                                        + ": referenced by "
                                        + owner
                                        + "."
                                        + key.name());
                }
            }
        }
        return new DropTableQuery(query.originalSql(), target, query.cascade());
    }

    private static Column findColumn(TableSchema schema, String name) {
        return schema.columns().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .or(
                        () ->
                                schema.columns().stream()
                                        .filter(c -> Scope.eq(c.name(), name))
                                        .findFirst())
                .orElseThrow(
                        () ->
                                new ValidationException(
                                        "Column '"
                                                + schema.table()
                                                + "."
                                                + name
                                                + "' does not exist"));
    }

    private static boolean containsColumn(Expr expr) {
        if (expr instanceof ColumnRef) return true;
        if (expr instanceof Expr.Binary b)
            return containsColumn(b.left()) || containsColumn(b.right());
        return false;
    }
}
