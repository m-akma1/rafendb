package org.rafendb.common.error;

/** The component that raised an error; prefixes the exception message. */
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

    /**
     * Returns the abbreviation, for example {@code "SM"}.
     *
     * @return the short name
     */
    public String shortName() {
        return shortName;
    }

    /**
     * Returns the full name, for example {@code "Storage Manager"}.
     *
     * @return the display name
     */
    public String displayName() {
        return displayName;
    }
}
