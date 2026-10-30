package org.rafendb.common.plan;

import java.util.List;

/** Tree boundary supplied by QO; concrete payloads remain provisional locally. */
public sealed interface PlanNode
        permits TableScanNode,
                IndexScanNode,
                SelectionPlanNode,
                ProjectionPlanNode,
                JoinPlanNode,
                CartesianProductPlanNode,
                SortNode,
                UpdatePlanNode,
                InsertPlanNode,
                DeletePlanNode,
                TransactionPlanNode,
                CreateIndexPlanNode {
    /** Returns ordered children. Local placeholders return immutable, non-null lists. */
    List<PlanNode> children();

    /** One-line description excluding children, for EXPLAIN; never parsed for execution. */
    String describe();
}
