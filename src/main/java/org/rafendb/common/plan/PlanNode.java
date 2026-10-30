package org.rafendb.common.plan;

import java.util.List;

public interface PlanNode {
    List<PlanNode> children();

    String describe();
}
