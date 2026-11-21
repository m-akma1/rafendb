package org.rafendb.optimizer.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.rafendb.common.error.OptimizerRuntimeException.SyntaxException;
import org.rafendb.common.query.CreateIndexQuery;
import org.rafendb.common.query.CreateTableQuery;
import org.rafendb.common.query.CreateTableQuery.ColumnDefinition;
import org.rafendb.common.query.CreateTableQuery.Reference;
import org.rafendb.common.query.DropTableQuery;
import org.rafendb.common.query.ParsedQuery;
import org.rafendb.common.query.TransactionQuery;
import org.rafendb.common.query.TransactionQuery.Kind;
import org.rafendb.common.schema.IndexType;
import org.rafendb.common.token.Token;
import org.rafendb.common.type.DataType;

/**
 * Parses transaction and schema commands without consulting the catalog.
 */
final class CommandParser {
    private CommandParser() {}

    /**
     * Parses one transaction or schema command.
     *
     * @param sql the original SQL text
     * @param tokens the Tokenizer output, non-empty and ending with EOF
     * @return the structured command
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
            if (in.acceptKeyword("INDEX")) query = createIndex(sql, in);
            else if (in.acceptKeyword("TABLE")) query = createTable(sql, in);
            else throw in.error(in.peek(), "INDEX or TABLE");
        } else if (first.isKeyword("DROP")) {
            in.expectKeyword("TABLE");
            String table = in.expectSimpleName("table name");
            boolean cascade = in.acceptWord("CASCADE");
            if (!cascade) in.acceptWord("RESTRICT");
            query = new DropTableQuery(sql, table, cascade);
        } else {
            throw in.error(first, "BEGIN, COMMIT, ABORT, CREATE or DROP");
        }
        in.expectEnd();
        return query;
    }

    private static ParsedQuery createIndex(String sql, TokenCursor in) {
        String name = in.expectIdentifier("index name");
        in.expectKeyword("ON");
        String table = in.expectIdentifier("table name");
        in.expectPunct("(");
        String column = in.expectIdentifier("column name");
        in.expectPunct(")");
        return new CreateIndexQuery(sql, name, table, column, indexType(in));
    }

    // USING, BTREE and HASH are matched by text because the Tokenizer may class them either way.
    private static IndexType indexType(TokenCursor in) {
        if (!in.acceptWord("USING") || in.acceptWord("BTREE")) {
            return IndexType.B_PLUS_TREE;
        }
        if (in.acceptWord("HASH")) {
            return IndexType.HASH;
        }
        throw in.error(in.peek(), "BTREE or HASH");
    }

    private static CreateTableQuery createTable(String sql, TokenCursor in) {
        String table = in.expectSimpleName("table name");
        List<ColumnDefinition> columns = new ArrayList<>();
        List<List<String>> primaryKeys = new ArrayList<>();
        List<Reference> foreignKeys = new ArrayList<>();
        in.expectPunct("(");
        do {
            String name = in.expectSimpleName("column name or key declaration");
            if (name.equalsIgnoreCase("PRIMARY") && in.acceptWord("KEY")) {
                primaryKeys.add(columnList(in));
            } else if (name.equalsIgnoreCase("FOREIGN") && in.acceptWord("KEY")) {
                List<String> local = columnList(in);
                if (!in.acceptWord("REFERENCES")) throw in.error(in.peek(), "REFERENCES");
                String target = in.expectSimpleName("referenced table");
                List<String> remote = in.peek().isPunctuation("(") ? columnList(in) : List.of();
                foreignKeys.add(new Reference(local, target, remote));
            } else {
                columns.add(columnDefinition(name, in));
                if (in.acceptWord("PRIMARY")) {
                    if (!in.acceptWord("KEY")) throw in.error(in.peek(), "KEY");
                    primaryKeys.add(List.of(name));
                }
            }
        } while (in.acceptPunct(","));
        in.expectPunct(")");
        return new CreateTableQuery(sql, table, columns, primaryKeys, foreignKeys);
    }

    private static ColumnDefinition columnDefinition(String name, TokenCursor in) {
        Token token = in.peek();
        String typeName = in.expectIdentifier("column type");
        DataType type =
                switch (typeName.toUpperCase(Locale.ROOT)) {
                    case "INTEGER" -> DataType.INTEGER;
                    case "FLOAT" -> DataType.FLOAT;
                    case "CHAR" -> DataType.CHAR;
                    case "VARCHAR" -> DataType.VARCHAR;
                    default -> throw in.error(token, "INTEGER, FLOAT, CHAR or VARCHAR");
                };
        int length = 0;
        if (type.isText()) {
            in.expectPunct("(");
            length = in.expectUnsignedInteger("text length");
            in.expectPunct(")");
        }
        return new ColumnDefinition(name, type, length);
    }

    private static List<String> columnList(TokenCursor in) {
        in.expectPunct("(");
        List<String> names = new ArrayList<>();
        do {
            names.add(in.expectSimpleName("key column"));
        } while (in.acceptPunct(","));
        in.expectPunct(")");
        return names;
    }
}
