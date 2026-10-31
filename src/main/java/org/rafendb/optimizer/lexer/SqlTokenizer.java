package org.rafendb.optimizer.lexer;

import java.util.List;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.token.Token;

public class SqlTokenizer implements Tokenizer {
    @Override
    public List<Token> tokenize(String sql) throws SyntaxException {
        throw new UnsupportedOperationException("SqlTokenizer is not implemented yet");
    }
}
