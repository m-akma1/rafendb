package org.rafendb.optimizer.binder;

import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.expr.Expr.Binary;
import org.rafendb.common.expr.Expr.ColumnRef;
import org.rafendb.common.expr.Expr.Literal;
import org.rafendb.common.expr.Expr.Op;
import org.rafendb.common.schema.Column;
import org.rafendb.common.type.DataType;

/** Resolves column references and checks expression types. */
final class ExprBinder {
    enum Kind {
        INTEGER,
        FLOAT,
        TEXT,
        BOOLEAN,
        NULL
    }

    record Typed(Expr expr, Kind kind, DataType dataType, String description) {}

    private final Scope scope;

    ExprBinder(Scope scope) {
        this.scope = scope;
    }

    Typed bind(Expr expr) {
        if (expr instanceof ColumnRef ref) {
            Scope.Resolved r = scope.resolve(ref.table(), ref.column());
            Column c = r.column();
            Kind kind =
                    c.type().isNumeric()
                            ? (c.type() == DataType.INTEGER ? Kind.INTEGER : Kind.FLOAT)
                            : Kind.TEXT;
            return new Typed(
                    new ColumnRef(r.table().name(), c.name(), r.offset()),
                    kind,
                    c.type(),
                    "column '" + r.table().name() + "." + c.name() + "'");
        }
        if (expr instanceof Expr.Null) return new Typed(expr, Kind.NULL, null, "NULL literal");
        if (expr instanceof Literal literal) {
            DataType type = literal.value().type();
            if (literal.value().isNull()) return new Typed(expr, Kind.NULL, null, "NULL literal");
            Kind kind =
                    type.isNumeric()
                            ? (type == DataType.INTEGER ? Kind.INTEGER : Kind.FLOAT)
                            : Kind.TEXT;
            return new Typed(expr, kind, type, "literal " + literal.value());
        }
        if (!(expr instanceof Binary binary))
            throw new ValidationException("Unsupported expression: " + expr);
        Typed left = bind(binary.left());
        Typed right = bind(binary.right());
        Op op = binary.op();
        Expr rewritten = new Binary(op, left.expr(), right.expr());
        if (op == Op.AND) {
            require(
                    (left.kind() == Kind.BOOLEAN || left.kind() == Kind.NULL)
                            && (right.kind() == Kind.BOOLEAN || right.kind() == Kind.NULL),
                    "AND requires boolean operands",
                    left,
                    right);
            return new Typed(rewritten, Kind.BOOLEAN, null, "boolean expression");
        }
        if (op == Op.ADD || op == Op.SUB || op == Op.MUL || op == Op.DIV || op == Op.MOD) {
            require(
                    (numeric(left.kind()) || left.kind() == Kind.NULL)
                            && (numeric(right.kind()) || right.kind() == Kind.NULL),
                    "Arithmetic requires numeric operands",
                    left,
                    right);
            if (left.kind() == Kind.NULL && right.kind() != Kind.NULL)
                rewritten = new Binary(op, typedNull(right.dataType()), right.expr());
            else if (right.kind() == Kind.NULL && left.kind() != Kind.NULL)
                rewritten = new Binary(op, left.expr(), typedNull(left.dataType()));
            Kind out =
                    left.kind() == Kind.FLOAT || right.kind() == Kind.FLOAT
                            ? Kind.FLOAT
                            : Kind.INTEGER;
            return new Typed(
                    rewritten,
                    out,
                    out == Kind.FLOAT ? DataType.FLOAT : DataType.INTEGER,
                    "arithmetic expression");
        }
        if (op == Op.EQ
                || op == Op.NE
                || op == Op.GT
                || op == Op.GE
                || op == Op.LT
                || op == Op.LE) {
            require(
                    left.kind() == Kind.NULL
                            || right.kind() == Kind.NULL
                            || numeric(left.kind()) && numeric(right.kind())
                            || left.kind() == Kind.TEXT && right.kind() == Kind.TEXT,
                    "Cannot compare incompatible types",
                    left,
                    right);
            if (left.kind() == Kind.NULL && right.dataType() != null)
                rewritten = new Binary(op, typedNull(right.dataType()), right.expr());
            else if (right.kind() == Kind.NULL && left.dataType() != null)
                rewritten = new Binary(op, left.expr(), typedNull(left.dataType()));
            return new Typed(rewritten, Kind.BOOLEAN, null, "boolean expression");
        }
        throw new ValidationException("Unsupported operator: " + op);
    }

    /**
     * Binds a condition and checks that it is boolean.
     *
     * @param expression the condition, or {@code null} for "no condition"
     * @param clause clause name for error messages, e.g. {@code WHERE}
     * @return the bound condition with every column offset set, or {@code null} if none was given
     * @throws ValidationException if a name or type is invalid, or the condition is not boolean
     */
    Expr condition(Expr expression, String clause) {
        if (expression == null) return null;
        Typed typed = bind(expression);
        if (typed.kind() != Kind.BOOLEAN && typed.kind() != Kind.NULL)
            throw new ValidationException(clause + " condition must be a boolean expression");
        return typed.expr();
    }

    Expr assign(Expr expr, Column target, String table) {
        Typed typed = bind(expr);
        DataType expected = target.type();
        if (expr instanceof Expr.Null
                || expr instanceof Literal literal && literal.value().isNull()) {
            if (!target.nullable())
                throw new ValidationException(
                        "NULL is not allowed for column '" + table + "." + target.name() + "'");
            return new Literal(org.rafendb.common.type.Value.nullOf(expected));
        }
        if (QueryBinder.ALLOW_INT_TO_FLOAT_WIDENING
                && expected == DataType.FLOAT
                && typed.kind() == Kind.INTEGER
                && expr instanceof Literal literal) {
            return new Literal(org.rafendb.common.type.Value.ofFloat(literal.value().asInt()));
        }
        boolean compatible =
                expected.isText()
                        ? typed.kind() == Kind.TEXT
                        : expected == DataType.INTEGER
                                ? typed.kind() == Kind.INTEGER
                                : typed.kind() == Kind.FLOAT;
        if (!compatible)
            throw new ValidationException(
                    "Value for column '"
                            + table
                            + "."
                            + target.name()
                            + "' has incompatible type "
                            + typed.kind()
                            + "; expected "
                            + expected);
        if (expr instanceof Literal literal && !target.accepts(literal.value()))
            throw new ValidationException(
                    "Value does not fit column '"
                            + table
                            + "."
                            + target.name()
                            + "' ("
                            + expected
                            + ")");
        return typed.expr();
    }

    private static boolean numeric(Kind kind) {
        return kind == Kind.INTEGER || kind == Kind.FLOAT;
    }

    private static Expr typedNull(DataType type) {
        return new Literal(org.rafendb.common.type.Value.nullOf(type));
    }

    private static void require(boolean ok, String message, Typed left, Typed right) {
        if (!ok)
            throw new ValidationException(
                    message
                            + ": "
                            + left.description()
                            + " ("
                            + typeName(left)
                            + ") and "
                            + right.description()
                            + " ("
                            + typeName(right)
                            + ")");
    }

    private static String typeName(Typed typed) {
        return typed.dataType() == null ? typed.kind().toString() : typed.dataType().toString();
    }
}
