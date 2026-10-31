package org.rafendb.optimizer.parser;

import java.util.List;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.query.CreateIndexQuery;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.TransactionQuery;
import org.rafendb.common.query.TransactionQuery.Kind;
import org.rafendb.common.token.Token;

/**
 * Parses commands that need no optimization: BEGIN [TRANSACTION], COMMIT, ABORT, and CREATE INDEX
 * (QO-06). Syntax only; no catalog access.
 */
final class CommandParser {
    private CommandParser() {}

    /**
     * Parses one transaction command or CREATE INDEX statement.
     *
     * @param sql the original SQL text
     * @param tokens the Tokenizer output, non-empty and ending with EOF
     * @return a {@link TransactionQuery} or {@link CreateIndexQuery}
     * @throws SyntaxException if the statement is incomplete, malformed, or not such a command
     */
    static ParsedQuery parse(String sql, List<Token> tokens) {
        TokenCursor in = new TokenCursor(tokens);
        Token first = in.next();
        ParsedQuery query;
        if (first.isKeyword("BEGIN")) {
            in.acceptKeyword("TRANSACTION");
            query = new TransactionQuery(sql, Kind.BEGIN);
        } else if (first.isKeyword("COMMIT")) {
            query = new TransactionQuery(sql, Kind.COMMIT);
        } else if (first.isKeyword("ABORT")) {
            query = new TransactionQuery(sql, Kind.ABORT);
        } else if (first.isKeyword("CREATE")) {
            query = createIndex(sql, in);
        } else {
            throw in.error(first, "BEGIN, COMMIT, ABORT, or CREATE");
        }
        in.expectEnd();
        return query;
    }

    private static ParsedQuery createIndex(String sql, TokenCursor in) {
        in.expectKeyword("INDEX");
        String name = in.expectIdentifier("index name");
        in.expectKeyword("ON");
        String table = in.expectIdentifier("table name");
        in.expectPunct("(");
        String column = in.expectIdentifier("column name");
        in.expectPunct(")");
        return new CreateIndexQuery(sql, name, table, column);
    }
}
