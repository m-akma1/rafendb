package org.rafendb.processor.plan;

import org.rafendb.common.plan.QueryPlan;
import org.rafendb.processor.execution.ExecutionPlan;

/**
 * Rancangan batas internal QP untuk menyiapkan eksekusi dari plan QO.
 * Pemanggilan bersamaan harus menghasilkan objek eksekusi yang independen.
 * Translator membentuk eksekusi tanpa membuka operator atau mengubah storage.
 *
 * <p>Mock lokal tersedia sebagai fixture; keberadaannya bukan persyaratan mock
 * antarkomponen M1 untuk QP2. Bentuk hasil yang dipakai QP1 dan pemberian context
 * eksekusi masih perlu diselaraskan dengan QP1/QP5.
 */
public interface PlanTranslator {
    /**
     * Prepares a non-null supported plan without opening cursors or mutating storage.
     * @param plan non-null query plan
     * @return non-null prepared row or command execution
     * @throws NullPointerException if plan is null
     * @throws IllegalArgumentException if the plan is invalid or unsupported
     */
    ExecutionPlan translate(QueryPlan plan);
}
