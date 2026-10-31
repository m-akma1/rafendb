package org.rafendb.common.tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.type.Value;

/** Nilai baris sesuai urutan schema operator. Rid boleh null untuk hasil tanpa identitas record. */
public record Row(List<Value> values, Rid rid) {
    /** Returns the value at a zero-based position; SQL NULL is a typed Value.nullOf(type), distinct from cursor exhaustion. */
    public Value get(int index) {
        return values.get(index);
    }

    /** Returns the number of values. */
    public int size() {
        return values.size();
    }

    /** Menggabungkan nilai outer lalu inner tanpa mengubah sumber. Hasil gabungan tidak memiliki Rid. */
    public static Row concat(Row outer, Row inner) {
        Objects.requireNonNull(outer, "outer");
        Objects.requireNonNull(inner, "inner");
        var values = new ArrayList<>(outer.values());
        values.addAll(inner.values());
        return new Row(values, null);
    }

    public Row {
        values = List.copyOf(values);
    }
}
