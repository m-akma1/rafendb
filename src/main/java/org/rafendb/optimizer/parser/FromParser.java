package org.rafendb.optimizer.parser;

import org.rafendb.common.error.OptimizerRuntimeException.SyntaxException;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.FromItem.TableRef;

final class FromParser {
    private final TokenCursor in;

    /**
     * Creates a parser over the shared token cursor.
     *
     * @param in the cursor positioned right after the FROM keyword
     */
    FromParser(TokenCursor in) {
        this.in = in;
    }

    /**
     * Parses the whole FROM clause.
     *
     * @return the join tree, grouped from left to right
     * @throws SyntaxException if a table, ON, or condition is missing or malformed
     */
    FromItem parse() {
        // TODO (QO-02, QO-03): see the steps in the class comment.
        throw new UnsupportedOperationException("TODO QO-03: FromParser.parse");
    }

    /**
     * Reads one table name.
     *
     * <p>TODO: {@code in.expectIdentifier("table name")}. The Tokenizer returns {@code a.b} as one
     * identifier, so reject a name containing '.' with {@code TokenCursor.syntaxError("Table name
     * cannot contain '.': '" + name + "'", token)}; this check moves here from {@code
     * SelectParser.fromTables}.
     */
    private TableRef table() {
        throw new UnsupportedOperationException("TODO QO-02: FromParser.table");
    }
}
