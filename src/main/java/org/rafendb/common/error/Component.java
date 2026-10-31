package org.rafendb.common.error;

/**
 * Axis A: which supergroup component raised the error.
 * Fixed per component base class via {@link RafenDBError#component()}.
 */
public enum Component {
    /**Query Processor */
    QP("QP", "Query Processor"),
    /**Query Optimizer */
    QO("QO", "Query Optimizer"),
    /**Storage Manager */
    SM("SM", "Storage Manager"),
    /**Concurrency Control Manager */
    CCM("CCM", "Concurrency Control Manager"),
    /**Failure Recovery Manager */
    FRM("FRM", "Failure Recovery Manager"),
    /**General */
    GENERAL("GEN", "General");

    private final String shortName;
    private final String displayName;

    Component(String shortName, String displayName) {
        this.shortName = shortName;
        this.displayName = displayName;
    }

    public String shortName() {
        return shortName;
    }

    public String displayName() {
        return displayName;
    }
}
