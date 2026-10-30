package org.rafendb.common.token;

/** Jenis token hasil tokenisasi (QO-01). */
public enum TokenType {
    KEYWORD,
    IDENTIFIER,
    NUMBER_INT,
    NUMBER_FLOAT,
    STRING_LITERAL,
    OPERATOR_COMPARISON, // =, <>, !=, <, <=, >, >=
    OPERATOR_ARITHMETIC, // +, -, *, /, %
    PUNCTUATION, // ( ) , ; .
    EOF
}
