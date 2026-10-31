package org.rafendb.mock.processor;

import java.util.List;
import java.util.Objects;
import org.rafendb.api.processor.ExecutionPlan;
import org.rafendb.api.processor.PlanTranslator;
import org.rafendb.common.plan.*;
import org.rafendb.common.schema.ColumnSchema;

/**
 * Translator mock yang aman dipakai bersamaan. Hasil baris memakai kolom konfigurasi tanpa data.
 * Perintah menghasilkan nol baris terdampak dan status MOCK tanpa menjalankan plan.
 */
public final class MockPlanTranslator implements PlanTranslator {
    private final List<ColumnSchema> columns;

    /** Menyalin urutan kolom hasil dari pemanggil. Daftar dan setiap kolom tidak boleh null. */
    public MockPlanTranslator(List<ColumnSchema> columns) {
        this.columns = List.copyOf(columns);
    }

    /** {@inheritDoc} Mock hanya menolak plan null dan memilih bentuk hasil berdasarkan jenis root. */
    @Override
    public ExecutionPlan translate(QueryPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (plan.getRoot() instanceof InsertPlanNode
                || plan.getRoot() instanceof UpdatePlanNode
                || plan.getRoot() instanceof DeletePlanNode
                || plan.getRoot() instanceof CreateIndexPlanNode
                || plan.getRoot() instanceof TransactionPlanNode) {
            return new ExecutionPlan.Command(new DummyCommand());
        }
        return new ExecutionPlan.Rows(columns, new MockOperator(columns, List.of()));
    }

    private static final class DummyCommand implements ExecutionPlan.CommandAction {
        private boolean executed;

        @Override
        public ExecutionPlan.CommandResult execute() {
            if (executed) throw new IllegalStateException("Command already executed");
            executed = true;
            return new ExecutionPlan.CommandResult(0, "MOCK: no storage changes");
        }
    }
}
