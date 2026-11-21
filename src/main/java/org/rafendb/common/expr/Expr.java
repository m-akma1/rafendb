package org.rafendb.common.expr;

import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.type.Value;

/**
 * Expression tree produced by the query parser for WHERE conditions, JOIN ... ON conditions, and
 * the value part of SET. Nodes are immutable and thread-safe; "changing" a tree means building a
 * new one.
 */
public sealed interface Expr {

    /**
     * A reference to a column.
     *
     * @param table the table name or alias, or {@code null} if written without a prefix
     * @param column the column name, never {@code null}
     * @param offset the column's position in its input row, set by the binder; {@code -1} before
     *     binding
     * @param scopeDepth the number of enclosing query scopes to traverse; zero means the current
     *     query scope
     */
    record ColumnRef(String table, String column, int offset, int scopeDepth) implements Expr {

        /**
         * Creates an unbound reference, with {@code offset} {@code -1}.
         *
         * @param table the table name or alias, or {@code null} if written without a prefix
         * @param column the column name, never {@code null}
         */
        public ColumnRef(String table, String column) {
            this(table, column, -1, 0);
        }

        /** Creates a reference with an offset and no enclosing-scope qualification. */
        public ColumnRef(String table, String column, int offset) {
            this(table, column, offset, 0);
        }
    }

    /**
     * A typed constant. A NULL written in SQL has no type yet and is a {@link Null} instead; a
     * typed SQL NULL ({@link Value#nullOf}) may appear once the binder knows the type.
     *
     * @param value the typed constant
     */
    record Literal(Value value) implements Expr {}

    /** An untyped SQL NULL literal, as written in the query. The binder gives it a type. */
    record Null() implements Expr {}

    /**
     * A binary operation. {@code AND} chains are left-nested: {@code a AND b AND c} is {@code
     * AND(AND(a, b), c)}.
     *
     * @param op the operator
     * @param left the left operand
     * @param right the right operand
     */
    record Binary(Op op, Expr left, Expr right) implements Expr {}

    record ScalarSubquery(SelectQuery query) implements Expr {}

    record InSubquery(Expr value, SelectQuery query) implements Expr {}

    record ExistsSubquery(SelectQuery query) implements Expr {}

    /** Operators: arithmetic, comparison, and logical AND. */
    enum Op {
        ADD,
        SUB,
        MUL,
        DIV,
        MOD,
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE,
        AND
    }
}