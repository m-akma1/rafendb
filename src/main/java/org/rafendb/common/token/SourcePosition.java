package org.rafendb.common.token;

/**
 * A position in the original SQL source text, used to report syntax errors
 * with a precise location (QO-01).
 *
 * <p>Fields are public for compact construction and direct access; accessors
 * are provided as well. Instances are mutable, but callers are expected to
 * treat them as value objects and not mutate them after publication.</p>
 */
public class SourcePosition {
    /** Zero-based character offset from the start of the source. */
    public int offset;

    /** One-based line number. */
    public int line;

    /** One-based column number within the line. */
    public int column;

    /** The position at the start of any source: offset 0, line 1, column 1. */
    public static final SourcePosition START = new SourcePosition(0, 1, 1);

    /**
     * Creates a source position.
     *
     * @param offset zero-based character offset
     * @param line   one-based line number
     * @param column one-based column number
     */
    public SourcePosition(int offset, int line, int column) {
        this.offset = offset;
        this.line = line;
        this.column = column;
    }

    /**
     * Returns the zero-based character offset.
     *
     * @return the offset
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Returns the one-based line number.
     *
     * @return the line
     */
    public int getLine() {
        return line;
    }

    /**
     * Returns the one-based column number.
     *
     * @return the column
     */
    public int getColumn() {
        return column;
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
