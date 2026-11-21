package org.rafendb.processor.subquery;

import java.util.Objects;
import org.rafendb.api.optimizer.QueryOptimizer;
import org.rafendb.common.error.OptimizerInternalException;
import org.rafendb.common.plan.QueryPlan;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.operator.limit.LimitOp;
import org.rafendb.processor.plan.PlanTranslator;

/**
 * Runner subquery yang beneran jalan: query dalam dioptimasi QO dulu, terus plannya diterjemahin
 * jadi operator sama translator QP. Translator-nya punya sesi, jadi subquery bersarang juga lewat
 * SubqueryEvaluator yang sama.
 *
 * <p>LIMIT query dalam dipasang lewat LimitOp.wrap, soalnya QO ga naruh LIMIT di plan.
 *
 * <p>Ga thread-safe (optimizer QO juga ga dijamin thread-safe), satu instance buat satu sesi.
 */
public final class PlanSubqueryRunner implements SubqueryRunner {
    private final QueryOptimizer optimizer;
    private final PlanTranslator translator;

    /**
     * Bikin runner dari optimizer sama translator sesi.
     *
     * @param optimizer optimizer QO buat query dalam
     * @param translator translator QP yang factory-nya megang SubqueryEvaluator
     * @throws NullPointerException kalau optimizer atau translator null
     */
    public PlanSubqueryRunner(QueryOptimizer optimizer, PlanTranslator translator) {
        this.optimizer = Objects.requireNonNull(optimizer, "optimizer");
        this.translator = Objects.requireNonNull(translator, "translator");
    }

    /**
     * Optimasi dulu, baru translate. Error unchecked dari optimizer atau translator dilempar terus.
     *
     * @throws IllegalStateException kalau optimasi gagal atau plannya bukan pembacaan baris
     */
    @Override
    public ExecutionPlan.Rows prepare(SelectQuery query) {
        QueryPlan plan;
        try {
            plan = optimizer.optimize(query);
        } catch (OptimizerInternalException e) {
            throw new IllegalStateException("Subquery optimization failed", e);
        }
        ExecutionPlan prepared = translator.translate(plan);
        if (prepared instanceof ExecutionPlan.Rows rows) {
            return LimitOp.wrap(rows, query.limit());
        }
        throw new IllegalStateException("Subquery plan must return rows");
    }
}
