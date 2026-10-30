package org.rafendb.optimizer.lexer;

import java.util.List;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.token.Token;

public interface Tokenizer {
    List<Token> tokenize(String Sql) throws SyntaxException;
}
