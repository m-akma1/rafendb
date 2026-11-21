package org.rafendb.processor.subquery;

import org.rafendb.common.query.SelectQuery;
import org.rafendb.processor.execution.ExecutionPlan;

/**
 * Nyiapin operator buat query di dalam subquery. Query yang masuk udah ga korelasi lagi, kolom
 * luarnya udah diganti Literal sama SubqueryEvaluator.
 */
public interface SubqueryRunner {
    /**
     * Ubah query dalam jadi operator. Operatornya jangan dibuka dulu, nanti dibuka sama ditutup
     * SubqueryEvaluator.
     *
     * @param query query dalam yang udah di-bind QO, tanpa kolom luar
     * @return baris hasil query, schema-nya ikut projection query
     */
    ExecutionPlan.Rows prepare(SelectQuery query);
}
