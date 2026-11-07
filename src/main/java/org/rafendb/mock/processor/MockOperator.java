package org.rafendb.mock.processor;

import java.util.List;
import org.rafendb.api.processor.Operator;
import org.rafendb.common.schema.ColumnSchema;
import org.rafendb.common.tuple.ResultRow;

/** Operator mock dengan data di memori untuk integrasi QP4. Belum menjalankan scan, join, atau sort. */
public final class MockOperator implements Operator {
    private final List<ColumnSchema> schema;
    private final List<ResultRow> rows;
    private int position;
    private boolean opened;

    /** Menyalin data masukan dan menolak row yang jumlah nilainya berbeda dari jumlah kolom schema. */
    public MockOperator(List<ColumnSchema> schema, List<ResultRow> rows) {
        this.schema = List.copyOf(schema);
        this.rows = List.copyOf(rows);
        for (ResultRow row : this.rows) {
            if (row.size() != this.schema.size()) {
                throw new IllegalArgumentException("ResultRow size differs from operator schema");
            }
        }
    }

    /** Mengembalikan schema yang tetap tersedia meskipun row kosong atau operator ditutup. */
    @Override
    public List<ColumnSchema> schema() {
        return schema;
    }

    /** Mengulang pembacaan dari row pertama, termasuk jika sebelumnya sudah dibuka atau ditutup. */
    @Override
    public void open() {
        position = 0;
        opened = true;
    }

    /**
     * Mengambil row pada posisi saat ini lalu memajukan posisi. Jika habis, hasilnya tetap null.
     * @throws IllegalStateException jika belum dibuka atau sudah ditutup
     */
    @Override
    public ResultRow next() {
        if (!opened) throw new IllegalStateException("Cursor is not open");
        return position < rows.size() ? rows.get(position++) : null;
    }

    /** Menutup pembacaan sampai open dipanggil lagi. Aman dipanggil berulang kali. */
    @Override
    public void close() {
        opened = false;
    }
}
