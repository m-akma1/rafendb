package org.rafendb.optimizer.parser;

import java.util.ArrayList;
import java.util.List;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.token.Token;

final class SelectParser {
    private SelectParser() {}

    static ParsedQuery parse(String sql, List<Token> tokens) {
        TokenCursor in = new TokenCursor(tokens);
        // Clause order is fixed: SELECT list -> FROM -> [WHERE] -> [ORDER BY].
        in.expectKeyword("SELECT");
        List<Expr> projection = selectList(in);
        in.expectKeyword("FROM");
        List<String> tables = fromTables(in);
        Expr where = optionalWhere(in);
        List<OrderByItem> orderBy = optionalOrderBy(in);
        in.expectEnd();
        return new SelectQuery(sql, projection, tables, null, where, orderBy);
    }

    private static List<Expr> selectList(TokenCursor in) {
        List<Expr> columns = new ArrayList<>();
        do {
            Token t = in.peek();
            if (t.type() == Token.TokenType.ARITHMETIC_OPERATOR && t.text().equals("*")) {
                in.next();
                columns.add(new Expr.ColumnRef(null, "*"));
            } else if (t.type() == Token.TokenType.IDENTIFIER) {
                columns.add(column(in, true));
            } else {
                throw in.error(t, "column name or '*'");
            }
        } while (in.acceptPunct(","));
        return columns;
    }

    private static List<String> fromTables(TokenCursor in) {
        List<String> tables = new ArrayList<>();
        do {
            Token t = in.peek();
            String tableName = in.expectIdentifier("table name");
            if (tableName.indexOf('.') != -1) {
                throw TokenCursor.syntaxError(
                        "Table name must not contain a dot: '" + tableName + "'", t);
            }
            tables.add(tableName);
        } while (in.acceptPunct(","));
        return tables;
    }

    private static Expr optionalWhere(TokenCursor in) {
        if (in.acceptKeyword("WHERE")) {
            return new ExpressionParser(in).parseCondition();
        }
        return null;
    }

    private static List<OrderByItem> optionalOrderBy(TokenCursor in) {
        List<OrderByItem> orderBy = new ArrayList<>();
        if (in.acceptKeyword("ORDER")) {
            in.expectKeyword("BY");
            do {
                Expr columnName = column(in, false);
                boolean ascending = true;
                if (in.acceptKeyword("DESC")) {
                    ascending = false;
                } else {
                    in.acceptKeyword("ASC");
                }
                orderBy.add(new OrderByItem(columnName, ascending));
            } while (in.acceptPunct(","));
        }
        return orderBy;
    }

    private static ColumnRef column(TokenCursor in, boolean allowStar) {
        Token t = in.peek();
        if (t.type() != Token.TokenType.IDENTIFIER) {
            throw in.error(t, allowStar ? "column name or '*'" : "column name");
        }
        in.next();
        String[] parts = t.text().split("\\.", 2);
        ColumnRef ref;
        if (parts.length == 1) {
            ref = new Expr.ColumnRef(null, parts[0]);
        } else {
            ref = new Expr.ColumnRef(parts[0], parts[1]);
        }
        if (!allowStar && ref.column().equals("*")) {
            throw TokenCursor.syntaxError("'*' is not allowed here: '" + t.text() + "'", t);
        }
        return ref;
    }
}
