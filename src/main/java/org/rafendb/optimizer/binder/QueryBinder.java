package org.rafendb.optimizer.binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.error.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.query.CreateIndexQuery;
import org.rafendb.common.query.DeleteQuery;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.query.TransactionQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.optimizer.catalog.Catalog;

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
        if (parsed instanceof SelectQuery select) return bind(select, false);
        if (parsed instanceof InsertQuery insert) return bindInsert(insert);
        if (parsed instanceof UpdateQuery update) return bindUpdate(update);
        if (parsed instanceof DeleteQuery delete) return bindDelete(delete);
        if (parsed instanceof CreateIndexQuery index) return bindIndex(index);
        if (parsed instanceof TransactionQuery) return parsed;
        throw new ValidationException("Unsupported query type: " + parsed.getClass().getName());
    }

    /**
     * Binds a SELECT with an explicit natural-join marker until that marker is part of SelectQuery.
     *
     * @param query non-null SELECT query
     * @param natural whether shared columns should form a natural join
     * @return the validated, canonical SELECT
     * @throws NullPointerException if {@code query} is {@code null}
     * @throws ValidationException if catalog names, expressions, or natural-join types are invalid
     */
    public SelectQuery bind(SelectQuery query, boolean natural) {
        // TODO(contract): replace this hook when SelectQuery carries join kinds.
        Objects.requireNonNull(query, "query");
        Scope scope = new Scope(catalog, query.sourceTables());
        Expr join = query.joinCondition();
        if (natural && join == null) {
            Expr generated = NaturalJoinResolver.resolve(scope);
            join = generated;
        }
        ExprBinder binder = new ExprBinder(scope);
        if (join != null) {
            binder.requireBoolean(join, "JOIN");
            join = binder.bind(join).expr();
        }
        Expr where = query.whereCondition();
        if (where != null) {
            binder.requireBoolean(where, "WHERE");
            where = binder.bind(where).expr();
        }
        List<Expr> projection = StarExpander.expand(query.projection(), scope, natural);
        List<Expr> boundProjection = new ArrayList<>();
        for (Expr expression : projection) boundProjection.add(binder.bind(expression).expr());
        List<OrderByItem> orderBy = new ArrayList<>();
        for (OrderByItem item : query.orderBy()) {
            orderBy.add(new OrderByItem(binder.bind(item.expression()).expr(), item.ascending()));
        }
        return new SelectQuery(
                query.originalSql(),
                List.copyOf(boundProjection),
                scope.tables().stream().map(Scope.Table::name).toList(),
                join,
                where,
                List.copyOf(orderBy));
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
        Scope scope = new Scope(catalog, List.of(schema.table()));
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
        Scope scope = new Scope(catalog, List.of(schema.table()));
        ExprBinder binder = new ExprBinder(scope);
        Expr value = binder.assign(query.value(), target, schema.table());
        Expr where = query.where();
        if (where != null) {
            binder.requireBoolean(where, "WHERE");
            where = binder.bind(where).expr();
        }
        return new UpdateQuery(query.originalSql(), schema.table(), target.name(), value, where);
    }

    private ParsedQuery bindDelete(DeleteQuery query) {
        TableSchema schema = schema(query.table());
        Expr where = query.where();
        if (where != null) {
            ExprBinder binder = new ExprBinder(new Scope(catalog, List.of(schema.table())));
            binder.requireBoolean(where, "WHERE");
            where = binder.bind(where).expr();
        }
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
