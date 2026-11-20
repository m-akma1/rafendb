package org.rafendb.common;

import java.util.Locale;

/**
 * One of the RafenDB components. Used to attribute errors (it prefixes exception messages) and to
 * choose which components a driver replaces with mocks.
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

    /**
     * Finds a component by its short name, ignoring case: {@code "sm"} gives {@link #SM}.
     *
     * @param shortName the abbreviation, such as {@code "SM"} or {@code "frm"}
     * @return the component
     * @throws IllegalArgumentException if no component has that short name
     */
    public static Component fromShortName(String shortName) {
        String key = shortName.strip().toUpperCase(Locale.ROOT);
        for (Component component : values()) {
            if (component.shortName.equals(key)) {
                return component;
            }
        }
        throw new IllegalArgumentException("unknown component: " + shortName);
    }
}
