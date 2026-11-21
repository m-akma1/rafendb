/**
 * Heuristic plan rewrites (QO-09, QO-10). {@link PlanRewriter} applies selection pushdown and projection pushdown to a plan tree, pass after pass, until a pass changes nothing or a pass limit is reached.
 *
 * <p>Owner: G3 Query Optimizer (QO4).
 */
package org.rafendb.optimizer.rewrite;
