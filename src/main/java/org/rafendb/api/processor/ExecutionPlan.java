package org.rafendb.api.processor;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.schema.ColumnSchema;

/** Hasil persiapan translator berupa pembacaan baris atau perintah yang siap dijalankan. */
public sealed interface ExecutionPlan {
    /** Schema bertipe tetap tersedia saat hasil kosong dan urutannya sesuai nilai row. */
    record Rows(List<ColumnSchema> columns, Operator operator) implements ExecutionPlan {
        public Rows {
            columns = List.copyOf(columns);
            Objects.requireNonNull(operator, "operator");
            if (!columns.equals(operator.schema()))
                throw new IllegalArgumentException("Headers differ from schema");
        }
    }

    /** Menyimpan perintah yang baru dijalankan saat execute dipanggil. */
    record Command(CommandAction action) implements ExecutionPlan {
        public Command {
            Objects.requireNonNull(action, "action");
        }
    }

    /** Aksi untuk satu eksekusi yang tidak aman dipakai bersamaan dan tidak boleh dijalankan dua kali. */
    interface CommandAction {
        /**
         * Menjalankan perintah sekali dan mengembalikan hasil yang tidak null.
         * @throws IllegalStateException jika dipanggil lebih dari sekali
         */
        CommandResult execute();
    }

    /** Status tidak boleh null dan jumlah baris terdampak tidak boleh negatif. Pembuatan index menghasilkan nol. */
    record CommandResult(long affectedRows, String status) {
        public CommandResult {
            if (affectedRows < 0) throw new IllegalArgumentException("Negative affected row count");
            Objects.requireNonNull(status, "status");
        }
    }
}
