package org.rafendb.server;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.query.QueryResult;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.Value;

/**
 * Turns a {@link QueryResult} into the text shown to the user (QP-01): a table with headers and a
 * row count for SELECT, a row count for data changes, and the status line otherwise.
 */
public final class ResultRenderer {
    private ResultRenderer() {}

    /**
     * Renders a result as text without a trailing newline.
     *
     * @param result the result to render, not null
     * @return the text to show the user
     */
    public static String render(QueryResult result) {
        return switch (result) {
            case QueryResult.Rows rows -> renderRows(rows);
            case QueryResult.Affected affected -> plural(affected.count(), "row") + " affected";
            case QueryResult.Status status -> status.message();
        };
    }

    private static String renderRows(QueryResult.Rows rows) {
        List<String> headers = rows.columns();
        List<List<String>> cells = new ArrayList<>();
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i).length();
        }
        for (Row row : rows.rows()) {
            List<String> line = new ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                String cell = cell(row.get(i));
                widths[i] = Math.max(widths[i], cell.length());
                line.add(cell);
            }
            cells.add(line);
        }

        String border = border(widths);
        StringBuilder sb = new StringBuilder();
        sb.append(border).append('\n');
        appendLine(sb, headers, widths);
        sb.append(border).append('\n');
        for (List<String> line : cells) {
            appendLine(sb, line, widths);
        }
        if (!cells.isEmpty()) {
            sb.append(border).append('\n');
        }
        sb.append('(').append(plural(cells.size(), "row")).append(')');
        return sb.toString();
    }

    /** Shows NULL as {@code NULL} and text without the quotes {@link Value#toString()} adds. */
    private static String cell(Value value) {
        if (value.isNull()) return "NULL";
        return value.type().isText() ? value.asText() : value.toString();
    }

    private static String border(int[] widths) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : widths) {
            sb.append("-".repeat(width + 2)).append('+');
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, List<String> cells, int[] widths) {
        sb.append('|');
        for (int i = 0; i < widths.length; i++) {
            String cell = cells.get(i);
            sb.append(' ').append(cell).append(" ".repeat(widths[i] - cell.length())).append(" |");
        }
        sb.append('\n');
    }

    private static String plural(long count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }
}
