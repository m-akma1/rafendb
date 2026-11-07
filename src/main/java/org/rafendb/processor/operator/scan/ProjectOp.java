package org.rafendb.processor.operator.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.Value;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Operator SELECT kolom (QP-05). Ngambil nilai kolom child sesuai urutan hasil binding QO,
 * hasilnya ga bawa RID. Posisi kolom dibaca dari offset ColumnRef, qualifier di schema output juga
 * ikut hasil binding QO (alias atau nama tabel).
 *
 * <p>Ga thread-safe, satu instance buat satu eksekusi.
 */
public final class ProjectOp implements Operator {
    private final Operator child;
    private final int[] offsets;
    private final List<ColumnSchema> schema;
    private boolean open;

    /**
     * Bikin ProjectOp, child belum dibuka. Schema output langsung disiapin dari schema child.
     *
     * @param child sumber baris, lifecycle-nya diurus ProjectOp
     * @param columns kolom output berurutan, isinya ColumnRef yang offset-nya udah diisi QO
     * @throws NullPointerException kalau child, columns, atau isi columns ada yang null
     * @throws IllegalArgumentException kalau columns kosong, ada yang bukan ColumnRef, atau offset
     *     di luar schema child
     */
    public ProjectOp(Operator child, List<Expr> columns) {
        this.child = Objects.requireNonNull(child, "child");
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Projection has no columns");
        }
        List<ColumnSchema> childSchema = child.schema();
        List<ColumnSchema> outputSchema = new ArrayList<>();
        offsets = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            Expr column = Objects.requireNonNull(columns.get(i), "column");
            if (!(column instanceof Expr.ColumnRef ref)) {
                throw new IllegalArgumentException("Projection only supports columns: " + column);
            }
            int offset = ref.offset();
            if (offset < 0 || offset >= childSchema.size()) {
                throw new IllegalArgumentException(
                        "Column offset is unbound or out of range: " + offset);
            }
            offsets[i] = offset;
            Expr.ColumnRef header = new Expr.ColumnRef(ref.table(), ref.column());
            outputSchema.add(new ColumnSchema(header, childSchema.get(offset).type()));
        }
        schema = List.copyOf(outputSchema);
    }

    /**
     * Buka child dari awal. Kalau child gagal dibuka, operator ini tetap ketutup, jadi next() bakal
     * error.
     */
    @Override
    public void open() {
        open = false;
        child.open();
        open = true;
    }

    /** Schema output yang udah disiapin di constructor. */
    @Override
    public List<ColumnSchema> schema() {
        return schema;
    }

    /**
     * Balikin baris berikutnya yang isinya cuma kolom yang dipilih (RID null), atau null kalau
     * child udah habis.
     *
     * @throws IllegalStateException kalau belum dibuka atau udah ditutup
     */
    @Override
    public ResultRow next() {
        if (!open) {
            throw new IllegalStateException("ProjectOp is not open");
        }
        ResultRow row = child.next();
        if (row == null) {
            return null;
        }
        List<Value> values = new ArrayList<>();
        for (int offset : offsets) {
            values.add(row.get(offset));
        }
        return new ResultRow(values, null);
    }

    /** Tutup child. Aman dipanggil sebelum open atau berkali-kali. */
    @Override
    public void close() {
        open = false;
        child.close();
    }
}
