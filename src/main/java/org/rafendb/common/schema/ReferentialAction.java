package org.rafendb.common.schema;

/**
 * The action on child rows when a referenced parent row is deleted. The Storage Manager only
 * stores it; the Query Processor enforces it and may report {@link #CASCADE} as unsupported.
 */
public enum ReferentialAction {
    /** The default: reject deleting a parent row that is still referenced. */
    RESTRICT,
    /** Delete the dependent child rows. */
    CASCADE
}
