package org.rafendb.optimizer.parser;

import java.util.List;
import org.rafendb.common.error.OptimizerRuntimeException.SyntaxException;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.token.Token;
import org.rafendb.optimizer.lexer.Tokenizer;
import org.rafendb.optimizer.lexer.TokenizerImpl;

public final class QueryParser {
    private final Tokenizer tokenizer;

    /** Creates a parser using the production {@link TokenizerImpl}. */
    public QueryParser() {
        this(new TokenizerImpl());
    }

    /**
     * Creates a parser with an injected tokenizer, for testing.
     *
     * @param tokenizer the tokenizer to use
     */
    public QueryParser(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Tokenizes and parses one statement.
     *
     * @param sql the original SQL text
     * @return the parsed statement
     * @throws SyntaxException if {@code sql} is not valid SQL (QO-01)
     */
    public ParsedQuery parse(String sql) {
        List<Token> tokens = tokenizer.tokenize(sql);
        Token first = tokens.get(0);
        if (first.isKeyword("SELECT")) {
            return SelectParser.parse(sql, tokens);
        }
        if (first.isKeyword("INSERT") || first.isKeyword("UPDATE") || first.isKeyword("DELETE")) {
            return DmlParser.parse(sql, tokens);
        }
        if (first.isKeyword("BEGIN")
                || first.isKeyword("COMMIT")
                || first.isKeyword("CREATE")
                || first.isKeyword("ABORT")) {
            return CommandParser.parse(sql, tokens);
        }
        throw TokenCursor.syntaxError(
                "Expected SELECT, UPDATE, INSERT, DELETE, BEGIN, COMMIT, ABORT or CREATE but found '"
                        + first.text()
                        + "'",
                first);
    }
}
