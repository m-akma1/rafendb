package org.rafendb.optimizer.parser;

import static org.rafendb.common.token.Token.TokenType.ARITHMETIC_OPERATOR;
import static org.rafendb.common.token.Token.TokenType.COMPARISON_OPERATOR;
import static org.rafendb.common.token.Token.TokenType.FLOAT_LITERAL;
import static org.rafendb.common.token.Token.TokenType.INTEGER_LITERAL;

import java.util.Map;
import java.util.function.Supplier;
import org.rafendb.common.error.OptimizerRuntimeException.SyntaxException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.Binary;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.expr.Expr.Literal;
import org.rafendb.common.expr.Expr.Null;
import org.rafendb.common.expr.Expr.Op;
import org.rafendb.common.token.Token;
import org.rafendb.common.token.Token.TokenType;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * The one expression parser shared by WHERE, JOIN ... ON, and SET (QO-04).
 *
 * <p>Precedence, highest first: primary (column, literal, parentheses), {@code * / %}, {@code + -},
 * comparison, {@code AND}. All binary operators are left-associative. A {@code -} directly before a
 * number is read as a negative literal, and {@code NULL} is an untyped {@link Null}.
 *
 * <p>Not thread-safe: use one instance per query. It stops at the first token that cannot continue
 * the expression; the caller decides whether that token is acceptable.
 */
final class ExpressionParser {
    private static final Map<String, Op> ADD = Map.of("+", Op.ADD, "-", Op.SUB);
    private static final Map<String, Op> MUL = Map.of("*", Op.MUL, "/", Op.DIV, "%", Op.MOD);
    private static final Map<String, Op> CMP =
            Map.of(
                    "=", Op.EQ, "<>", Op.NE, "!=", Op.NE, ">", Op.GT, ">=", Op.GE, "<", Op.LT, "<=",
                    Op.LE);

    private final TokenCursor in;

    /**
     * Creates a parser that reads from, and advances, the given cursor.
     *
     * @param in the shared token cursor
     */
    ExpressionParser(TokenCursor in) {
        this.in = in;
    }

    /**
     * Parses a WHERE / JOIN ... ON condition: comparisons joined by AND.
     *
     * @return the expression tree
     * @throws SyntaxException if the expression is incomplete or malformed
     */
    Expr parseCondition() {
        Expr left = parseComparison();
        while (in.acceptKeyword("AND")) {
            left = new Binary(Op.AND, left, parseComparison());
        }
        return left;
    }

    /**
     * Parses a SET value: arithmetic over columns and literals, no comparison or AND.
     *
     * @return the expression tree
     * @throws SyntaxException if the expression is incomplete or malformed
     */
    Expr parseValue() {
        return parseAdditive();
    }

    private Expr parseComparison() {
        Expr left = parseAdditive();
        Op op = opAt(COMPARISON_OPERATOR, CMP);
        if (op == null) {
            return left;
        }
        in.next();
        return new Binary(op, left, parseAdditive());
    }

    private Expr parseAdditive() {
        return leftAssoc(ARITHMETIC_OPERATOR, ADD, this::parseMultiplicative);
    }

    private Expr parseMultiplicative() {
        return leftAssoc(ARITHMETIC_OPERATOR, MUL, this::parsePrimary);
    }

    // A loop instead of right recursion makes 5 - 3 - 2 parse as (5 - 3) - 2.
    private Expr leftAssoc(TokenType type, Map<String, Op> ops, Supplier<Expr> operand) {
        Expr left = operand.get();
        Op op;
        while ((op = opAt(type, ops)) != null) {
            in.next();
            left = new Binary(op, left, operand.get());
        }
        return left;
    }

    private Expr parsePrimary() {
        Token t = in.next();
        return switch (t.type()) {
            case INTEGER_LITERAL, FLOAT_LITERAL -> number(t, false);
            case STRING_LITERAL -> new Literal(Value.ofText(DataType.VARCHAR, t.text()));
            case KEYWORD -> {
                if (!t.isKeyword("NULL")) {
                    throw in.error(t, "an operand");
                }
                yield new Null();
            }
            case IDENTIFIER -> {
                // NULL may be tokenized as a keyword or as an identifier.
                if (t.text().equalsIgnoreCase("NULL")) {
                    yield new Null();
                }
                // The Tokenizer returns table.column as a single identifier token.
                int dot = t.text().indexOf('.');
                yield dot < 0
                        ? new ColumnRef(null, t.text())
                        : new ColumnRef(t.text().substring(0, dot), t.text().substring(dot + 1));
            }
            case ARITHMETIC_OPERATOR -> {
                Token n = in.peek();
                boolean negativeNumber =
                        t.text().equals("-")
                                && (n.type() == INTEGER_LITERAL || n.type() == FLOAT_LITERAL);
                if (!negativeNumber) {
                    throw in.error(t, "an operand");
                }
                in.next();
                yield number(n, true);
            }
            case PUNCTUATION -> {
                if (!t.isPunctuation("(")) {
                    throw in.error(t, "an operand");
                }
                Expr inner = parseCondition();
                in.expectPunct(")");
                yield inner;
            }
            default -> throw in.error(t, "an operand");
        };
    }

    // Checks the token type first so a string literal '+' is never taken as an operator.
    private Op opAt(TokenType type, Map<String, Op> ops) {
        Token t = in.peek();
        return t.type() == type ? ops.get(t.text()) : null;
    }

    private static Expr number(Token t, boolean negative) {
        String text = (negative ? "-" : "") + t.text();
        try {
            if (t.type() == INTEGER_LITERAL) {
                return new Literal(Value.ofInt(Integer.parseInt(text)));
            }
            float f = Float.parseFloat(text);
            if (Float.isInfinite(f)) {
                throw new NumberFormatException(text);
            }
            return new Literal(Value.ofFloat(f));
        } catch (NumberFormatException e) {
            throw TokenCursor.syntaxError("Invalid or out-of-range number '" + text + "'", t);
        }
    }
}
