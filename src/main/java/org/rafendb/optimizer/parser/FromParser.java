package org.rafendb.optimizer.parser;

import org.rafendb.common.error.OptimizerRuntimeException.SyntaxException;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.FromItem.TableRef;
import org.rafendb.common.token.Token;

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
        FromItem left = table();
        while (true) {
            if (in.acceptPunct(",")) {
                left = new FromItem.CartesianProduct(left, table());
            } else if (in.acceptKeyword("JOIN")) {
                FromItem right = table();
                in.expectKeyword("ON");
                left = new FromItem.Join(left, right, new ExpressionParser(in).parseCondition());
            } else if (in.acceptKeyword("NATURAL")) {
                in.expectKeyword("JOIN");
                FromItem right = table();
                left = new FromItem.NaturalJoin(left, right);
            } else {
                break;
            }
        }
        return left;
    }

    private TableRef table() {
        Token t = in.peek();
        String name = in.expectIdentifier("table name");
        if (name.indexOf('.') >= 0) {
            throw TokenCursor.syntaxError("Table name cannot contain '.': '" + name + "'", t);
        }
        return new TableRef(name);
    }
}
