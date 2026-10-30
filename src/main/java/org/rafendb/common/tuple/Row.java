package org.rafendb.common.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.ColumnRef;
import org.rafendb.common.type.Value;

/** Immutable result row with ordered column identities; not a storage record or RID carrier. */
public record Row(List<ColumnRef> columns, List<Value> values) {
    /** Returns the value at a zero-based position; SQL NULL is Value.NullValue.INSTANCE. */
    public Value get(int index) {
        return values.get(index);
    }

    /** Returns the number of values. */
    public int size() {
        return values.size();
    }

    /** Creates outer followed by inner, preserving column identities and both source rows. */
    public static Row concat(Row outer, Row inner) {
        Objects.requireNonNull(outer, "outer");
        Objects.requireNonNull(inner, "inner");
        var columns = new ArrayList<>(outer.columns());
        columns.addAll(inner.columns());
        var values = new ArrayList<>(outer.values());
        values.addAll(inner.values());
        return new Row(columns, values);
    }

    public Row {
        columns = List.copyOf(columns);
        values = List.copyOf(values);
        if (columns.size() != values.size()) {
            throw new IllegalArgumentException("Column and value counts differ");
        }
    }
}
