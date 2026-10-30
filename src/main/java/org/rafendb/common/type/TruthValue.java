package org.rafendb.common.type;

/** Proposed predicate result pending QO2 agreement; only TRUE passes a filter or join. */
public enum TruthValue {
    TRUE,
    FALSE,
    UNKNOWN;

    /** Returns false for both FALSE and UNKNOWN. */
    public boolean isTrue() {
        return this == TRUE;
    }
}
