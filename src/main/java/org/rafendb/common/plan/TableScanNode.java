package org.rafendb.common.plan;

import java.util.List;

public class TableScanNode implements PlanNode {
    public String alias;
    public String table;

    public TableScanNode(String alias, String table) {
        this.alias = alias;
        this.table = table;
    }

    public String getAlias() {
        return alias;
    }

    public String getTable() {
        return table;
    }

    @Override
    public List<PlanNode> children() {
        return List.of();
    }

    @Override
    public String describe() {
        return "TABLE SCAN " + (alias == null ? table : table + " AS " + alias);
    }
}
