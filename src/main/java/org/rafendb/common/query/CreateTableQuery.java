package org.rafendb.common.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.ForeignKey;
import org.rafendb.common.schema.ReferentialAction;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.type.DataType;

/**
 * CREATE TABLE declarations in SQL order. The parser preserves each PRIMARY KEY clause so the
 * binder can reject multiple declarations. Binding makes all key column names explicit.
 */
public record CreateTableQuery(
        String originalSql,
        String table,
        List<ColumnDefinition> columns,
        List<List<String>> primaryKeys,
        List<Reference> foreignKeys)
        implements ParsedQuery {
    public CreateTableQuery {
        Objects.requireNonNull(originalSql);
        Objects.requireNonNull(table);
        columns = List.copyOf(columns);
        primaryKeys = primaryKeys.stream().map(List::copyOf).toList();
        foreignKeys = List.copyOf(foreignKeys);
    }

    public record ColumnDefinition(String name, DataType type, int length) {}

    /** An empty target column list means the referenced table's complete primary key. */
    public record Reference(List<String> columns, String targetTable, List<String> targetColumns) {
        public Reference {
            columns = List.copyOf(columns);
            Objects.requireNonNull(targetTable);
            targetColumns = List.copyOf(targetColumns);
        }
    }

    /** Builds the local column layout, which also serves as the target of a self-reference. */
    public List<Column> schemaColumns() {
        List<String> key = primaryKeys.isEmpty() ? List.of() : primaryKeys.getFirst();
        List<Column> result = new ArrayList<>();
        for (ColumnDefinition column : columns) {
            boolean primary = key.stream().anyMatch(column.name()::equalsIgnoreCase);
            result.add(
                    new Column(column.name(), column.type(), column.length(), !primary, primary));
        }
        return List.copyOf(result);
    }

    /** Builds the storage schema from the validated command returned by the optimizer facade. */
    public TableSchema toSchema() {
        List<ForeignKey> keys = new ArrayList<>();
        for (Reference reference : foreignKeys) {
            String name = "fk_" + table + "_" + (keys.size() + 1);
            keys.add(
                    new ForeignKey(
                            name,
                            reference.columns(),
                            reference.targetTable(),
                            reference.targetColumns(),
                            ReferentialAction.RESTRICT));
        }
        return new TableSchema(table, schemaColumns(), keys);
    }
}
