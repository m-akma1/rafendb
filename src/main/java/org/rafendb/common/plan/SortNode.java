package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.ColumnRef;

/** Local placeholder payload; only the node name and PlanNode boundary were supplied by QO. */
public record SortNode(PlanNode input, ColumnRef column, Direction direction) implements PlanNode {
    public SortNode {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(direction, "direction");
    }

    @Override
    public List<PlanNode> children() {
        return List.of(input);
    }

    @Override
    public String describe() {
        return "SortNode";
    }

    public enum Direction {
        ASC,
        DESC
    }
}
