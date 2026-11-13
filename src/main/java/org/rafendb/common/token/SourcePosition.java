package org.rafendb.common.token;

import java.io.Serializable;
import java.util.Objects;

/**
 * A position in the original SQL source text, used to report syntax errors
 * with a precise location (QO-01).
 *
 * <p>Fields are public for compact construction and direct access; accessors
 * are provided as well. Instances are mutable, but callers are expected to
 * treat them as value objects and not mutate them after publication.</p>
 */
public record SourcePosition(int offset, int line, int column) implements Serializable {
    /** Unique identifier for serialization. */
    private static final long serialVersionUID = 1L;

    /** The position at the start of any source: offset 0, line 1, column 1. */
    public static final SourcePosition START = new SourcePosition(0, 1, 1);

    /**
     * Creates a source position.
     *
     * @param offset zero-based character offset
     * @param line   one-based line number
     * @param column one-based column number
     */
    public SourcePosition {
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(column, "column");
    }

    /**
     * Returns a human-readable form of this position, e.g. {@code "line 3, column 7"}.
     *
     * @return the formatted position
     */
    @Override
    public String toString() {
        return "line " + line + ", column " + column;
    }
}
