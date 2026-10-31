package org.rafendb.common.plan;

import java.util.List;

public class IndexScanNode implements PlanNode {
    private String alias;
    private String table;
    private IndexScanDescriptor descriptor;

    public IndexScanNode(String alias, String table, IndexScanDescriptor descriptor) {
        this.alias = alias;
        this.table = table;
        this.descriptor = descriptor;
    }

    public String getAlias() {
        return alias;
    }

    public String getTable() {
        return table;
    }

    public IndexScanDescriptor getDescriptor() {
        return descriptor;
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
