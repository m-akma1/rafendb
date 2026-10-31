package org.rafendb.optimizer.parser;

import static org.rafendb.common.token.TokenType.NUMBER_FLOAT;
import static org.rafendb.common.token.TokenType.NUMBER_INT;
import static org.rafendb.common.token.TokenType.OPERATOR_ARITHMETIC;
import static org.rafendb.common.token.TokenType.OPERATOR_COMPARISON;

import java.util.Map;
import java.util.function.Supplier;
import org.rafendb.common.error.SyntaxException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.Binary;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.expr.Expr.Literal;
import org.rafendb.common.expr.Expr.Op;
import org.rafendb.common.token.Token;
import org.rafendb.common.token.TokenType;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;

/**
 * The one expression parser shared by WHERE, JOIN ... ON, and SET (QO-04).
 *
 * <p>Precedence, highest first: primary (column, literal, parentheses), {@code * / %}, {@code + -},
 * comparison, {@code AND}. All binary operators are left-associative. A {@code -} directly before a
 * number is read as a negative literal.
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
        Op op = opAt(OPERATOR_COMPARISON, CMP);
        if (op == null) {
            return left;
        }
        in.next();
        return new Binary(op, left, parseAdditive());
    }

    private Expr parseAdditive() {
        return leftAssoc(OPERATOR_ARITHMETIC, ADD, this::parseMultiplicative);
    }

    private Expr parseMultiplicative() {
        return leftAssoc(OPERATOR_ARITHMETIC, MUL, this::parsePrimary);
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
        return switch (t.getType()) {
            case NUMBER_INT, NUMBER_FLOAT -> number(t, false);
            case STRING_LITERAL -> new Literal(Value.ofText(DataType.VARCHAR, t.getText()));
            case IDENTIFIER -> {
                if (in.acceptPunct(".")) {
                    yield new ColumnRef(t.getText(), in.expectIdentifier("column name"));
                }
                yield new ColumnRef(null, t.getText());
            }
            case OPERATOR_ARITHMETIC -> {
                Token n = in.peek();
                boolean negativeNumber =
                        t.getText().equals("-")
                                && (n.getType() == NUMBER_INT || n.getType() == NUMBER_FLOAT);
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
        return t.getType() == type ? ops.get(t.getText()) : null;
    }

    private static Expr number(Token t, boolean negative) {
        String text = (negative ? "-" : "") + t.getText();
        try {
            if (t.getType() == NUMBER_INT) {
                return new Literal(Value.ofInt(Integer.parseInt(text)));
            }
            float f = Float.parseFloat(text);
            if (Float.isInfinite(f)) {
                throw new NumberFormatException(text);
            }
            return new Literal(Value.ofFloat(f));
        } catch (NumberFormatException e) {
            throw new SyntaxException("Invalid or out-of-range number '" + text + "'", t);
        }
    }
}
