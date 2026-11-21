package org.rafendb.processor.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.tuple.Row;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/** Runs a translated plan and turns what it produced into the result the client sees. */
final class ResultMapper {
    private ResultMapper() {}

    /**
     * Reads every row of a row-producing plan, then closes it. Run inside the statement's
     * transaction, so its read locks cover the whole read.
     *
     * @param plan a {@link ExecutionPlan.Rows}
     * @return the rows, with one header per column
     */
    static QueryResult.Rows rows(ExecutionPlan plan) {
        if (!(plan instanceof ExecutionPlan.Rows rows)) {
            throw new IllegalStateException("Expected a row-producing plan, got " + plan);
        }
        List<Row> read = new ArrayList<>();
        try (Operator operator = rows.operator()) {
            operator.open();
            for (ResultRow row = operator.next(); row != null; row = operator.next()) {
                read.add(new Row(row.values()));
            }
        }
        return new QueryResult.Rows(headers(rows.columns()), read);
    }

    /**
     * Column headers: the column name, or {@code table.column} when two output columns share a
     * name.
     */
    static List<String> headers(List<ColumnSchema> columns) {
        Map<String, Integer> seen = new HashMap<>();
        for (ColumnSchema column : columns) {
            seen.merge(column.reference().column(), 1, Integer::sum);
        }
        List<String> headers = new ArrayList<>(columns.size());
        for (ColumnSchema column : columns) {
            Expr.ColumnRef ref = column.reference();
            boolean shared = seen.get(ref.column()) > 1;
            headers.add(
                    shared && ref.table() != null
                            ? ref.table() + "." + ref.column()
                            : ref.column());
        }
        return headers;
    }
}
