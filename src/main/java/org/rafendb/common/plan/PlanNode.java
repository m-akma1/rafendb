package org.rafendb.common.plan;

import java.util.List;

/**
 * A node in a {@link QueryPlan} tree, describing one physical operator and its
 * parameters (table, condition, columns, index, join algorithm, etc.).
 *
 * <p>Leaf nodes return an empty child list; unary nodes one child; binary nodes two.
 * Implementations should be immutable and return an unmodifiable {@link #children()} list.
 * Per-node cost is not part of this interface; total cost lives in {@link QueryPlan}.</p>
 */
public interface PlanNode {

    /**
     * Returns the children of this node in execution (left-to-right) order.
     *
     * @return an unmodifiable list of children; never {@code null}, possibly empty for leaves
     */
    List<PlanNode> children();

    /**
     * Returns a short, single-line description of this node for plan display (QO-15),
     * including operation type and parameters.
     *
     * @return a non-{@code null} single-line description
     */
    String describe();
}