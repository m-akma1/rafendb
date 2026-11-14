package org.rafendb.processor.expr;

import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Evaluator QP-04 tanpa state, aman dipakai beberapa sesi bersamaan.
 * Referensi kolom harus sudah memiliki offset dari QO. NULL tanpa tipe ditolak.
 *
 * <p>Aritmatika INTEGER diperiksa terhadap overflow. Pembagian INTEGER memotong menuju nol.
 * Operand campuran menghasilkan FLOAT; hasil FLOAT yang tidak hingga ditolak.
 * Perbandingan angka memakai double agar INTEGER tidak dibulatkan lebih dulu ke float.
 * Teks mengikuti Value.compareTo, yaitu urutan UTF-16 yang case-sensitive.
 */
public final class DefaultExpressionEvaluator implements ExpressionEvaluator {
    /** Membuat evaluator tanpa dependency atau state eksekusi. */
    public DefaultExpressionEvaluator() {}

    /** {@inheritDoc} Ekspresi kondisi tidak dapat dikembalikan sebagai Value skalar. */
    @Override
    public Value evaluate(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        validateInput(expression, row, schema);
        return scalar(expression, row, schema);
    }

    /** {@inheritDoc} FALSE pada AND tidak mengevaluasi operand kanan. */
    @Override
    public TruthValue evaluateCondition(Expr condition, ResultRow row, List<ColumnSchema> schema) {
        validateInput(condition, row, schema);
        return condition(condition, row, schema);
    }

    /** Memeriksa bentuk input; tipe nilai kolom diperiksa saat kolom tersebut dibaca. */
    private static void validateInput(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(schema, "schema");
        if (row.size() != schema.size()) {
            throw new IllegalArgumentException("Row and schema sizes differ");
        }
    }

    /** Membaca literal, kolom berdasarkan offset, atau hasil aritmatika. */
    private static Value scalar(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        Objects.requireNonNull(expression, "expression operand");
        return switch (expression) {
            case Expr.Literal literal -> Objects.requireNonNull(literal.value(), "literal value");
            case Expr.ColumnRef column -> columnValue(column, row, schema);
            case Expr.Null _ ->
                    throw new IllegalArgumentException(
                            "SQL NULL must be typed by QO before evaluation");
            case Expr.Binary binary -> {
                Expr.Op op = Objects.requireNonNull(binary.op(), "operator");
                switch (op) {
                    case ADD, SUB, MUL, DIV, MOD -> {}
                    default ->
                            throw new IllegalArgumentException(
                                    "Expected scalar expression, got " + op);
                }
                yield arithmetic(
                        op,
                        scalar(binary.left(), row, schema),
                        scalar(binary.right(), row, schema));
            }
        };
    }

    /** Membaca posisi dari QO tanpa mencari ulang nama atau qualifier. */
    private static Value columnValue(
            Expr.ColumnRef column, ResultRow row, List<ColumnSchema> schema) {
        int offset = column.offset();
        if (offset < 0 || offset >= row.size()) {
            throw new IllegalArgumentException(
                    "Column offset is unbound or out of range: " + offset);
        }
        DataType expected = Objects.requireNonNull(schema.get(offset), "column schema").type();
        Value value = row.get(offset);
        if (value.type() != expected && !(value.type().isText() && expected.isText())) {
            throw new IllegalArgumentException(
                    "Column value type differs from schema at offset " + offset);
        }
        return value;
    }

    /** Menggabungkan kondisi dengan logika tiga nilai atau membandingkan dua nilai skalar. */
    private static TruthValue condition(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        Objects.requireNonNull(expression, "condition operand");
        if (!(expression instanceof Expr.Binary binary)) {
            throw new IllegalArgumentException("Expected comparison or AND condition");
        }
        Expr.Op op = Objects.requireNonNull(binary.op(), "operator");
        if (op == Expr.Op.AND) {
            TruthValue left = condition(binary.left(), row, schema);
            if (left == TruthValue.FALSE) return TruthValue.FALSE;
            TruthValue right = condition(binary.right(), row, schema);
            if (right == TruthValue.FALSE) return TruthValue.FALSE;
            return left == TruthValue.UNKNOWN || right == TruthValue.UNKNOWN
                    ? TruthValue.UNKNOWN
                    : TruthValue.TRUE;
        }
        switch (op) {
            case EQ, NE, GT, GE, LT, LE -> {}
            default -> throw new IllegalArgumentException("Expected comparison or AND, got " + op);
        }
        Value left = scalar(binary.left(), row, schema);
        Value right = scalar(binary.right(), row, schema);
        requireComparable(left, right);
        if (left.isNull() || right.isNull()) return TruthValue.UNKNOWN;
        int comparison =
                left.type().isNumeric()
                        ? Double.compare(number(left), number(right))
                        : left.compareTo(right);
        boolean matches =
                switch (op) {
                    case EQ -> comparison == 0;
                    case NE -> comparison != 0;
                    case GT -> comparison > 0;
                    case GE -> comparison >= 0;
                    case LT -> comparison < 0;
                    case LE -> comparison <= 0;
                    default -> throw new IllegalArgumentException("Not a comparison: " + op);
                };
        return matches ? TruthValue.TRUE : TruthValue.FALSE;
    }

    /** Menolak perbandingan antarfamily yang tidak kompatibel, termasuk NULL bertipe. */
    private static void requireComparable(Value left, Value right) {
        if (!(left.type().isNumeric() && right.type().isNumeric())
                && !(left.type().isText() && right.type().isText())) {
            throw new IllegalArgumentException(
                    "Incompatible comparison types: " + left.type() + " and " + right.type());
        }
    }

    /** Menghitung angka dengan promosi FLOAT dan propagasi SQL NULL bertipe. */
    private static Value arithmetic(Expr.Op op, Value left, Value right) {
        if (!left.type().isNumeric() || !right.type().isNumeric()) {
            throw new IllegalArgumentException("Arithmetic requires numeric operands");
        }
        DataType type =
                left.type() == DataType.FLOAT || right.type() == DataType.FLOAT
                        ? DataType.FLOAT
                        : DataType.INTEGER;
        if (left.isNull() || right.isNull()) return Value.nullOf(type);
        if ((op == Expr.Op.DIV || op == Expr.Op.MOD) && number(right) == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (type == DataType.INTEGER) {
            int a = left.asInt();
            int b = right.asInt();
            int result =
                    switch (op) {
                        case ADD -> Math.addExact(a, b);
                        case SUB -> Math.subtractExact(a, b);
                        case MUL -> Math.multiplyExact(a, b);
                        case DIV -> {
                            if (a == Integer.MIN_VALUE && b == -1)
                                throw new ArithmeticException("INTEGER overflow");
                            yield a / b;
                        }
                        case MOD -> a % b;
                        default -> throw new IllegalArgumentException("Not arithmetic: " + op);
                    };
            return Value.ofInt(result);
        }
        double a = number(left);
        double b = number(right);
        float result =
                (float)
                        switch (op) {
                            case ADD -> a + b;
                            case SUB -> a - b;
                            case MUL -> a * b;
                            case DIV -> a / b;
                            case MOD -> a % b;
                            default -> throw new IllegalArgumentException("Not arithmetic: " + op);
                        };
        if (!Float.isFinite(result)) throw new ArithmeticException("Non-finite FLOAT result");
        return Value.ofFloat(result);
    }

    /** Mengonversi angka tanpa lebih dulu mempersempit INTEGER ke float. */
    private static double number(Value value) {
        return value.type() == DataType.INTEGER ? (double) value.asInt() : value.asFloat();
    }
}
