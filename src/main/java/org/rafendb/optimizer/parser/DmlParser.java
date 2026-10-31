package org.rafendb.optimizer.parser;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.DeleteQuery;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.token.Token;

/** Parses UPDATE, INSERT, and DELETE statements (QO-05). Syntax only; no catalog access. */
final class DmlParser {
    private DmlParser() {}

    /**
     * Parses one UPDATE, INSERT, or DELETE statement.
     *
     * @param sql the original SQL text
     * @param tokens the Tokenizer output, non-empty and ending with EOF
     * @return an {@link UpdateQuery}, {@link InsertQuery}, or {@link DeleteQuery}
     * @throws SyntaxException if the statement is incomplete, malformed, or not a DML statement
     */
    static ParsedQuery parse(String sql, List<Token> tokens) {
        TokenCursor in = new TokenCursor(tokens);
        Token first = in.next();
        ParsedQuery query;
        if (first.isKeyword("UPDATE")) {
            query = update(sql, in);
        } else if (first.isKeyword("INSERT")) {
            query = insert(sql, in);
        } else if (first.isKeyword("DELETE")) {
            query = delete(sql, in);
        } else {
            throw in.error(first, "UPDATE, INSERT, or DELETE");
        }
        in.expectEnd();
        return query;
    }

    private static ParsedQuery update(String sql, TokenCursor in) {
        String table = in.expectIdentifier("table name");
        in.expectKeyword("SET");
        String column = in.expectIdentifier("column name");
        in.expectComparison("=");
        Expr value = new ExpressionParser(in).parseValue();
        return new UpdateQuery(sql, table, column, value, optionalWhere(in));
    }

    private static ParsedQuery insert(String sql, TokenCursor in) {
        in.expectKeyword("INTO");
        String table = in.expectIdentifier("table name");
        in.expectKeyword("VALUES");
        in.expectPunct("(");
        ExpressionParser expr = new ExpressionParser(in);
        List<Expr> values = new ArrayList<>();
        do {
            values.add(expr.parseValue());
        } while (in.acceptPunct(","));
        in.expectPunct(")");
        return new InsertQuery(sql, table, values);
    }

    private static ParsedQuery delete(String sql, TokenCursor in) {
        in.expectKeyword("FROM");
        String table = in.expectIdentifier("table name");
        return new DeleteQuery(sql, table, optionalWhere(in));
    }

    private static Expr optionalWhere(TokenCursor in) {
        return in.acceptKeyword("WHERE") ? new ExpressionParser(in).parseCondition() : null;
    }
}
