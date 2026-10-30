package org.rafendb.common.expr;

import java.util.Objects;
import org.rafendb.common.type.Value;

/** Proposed immutable expression tree shared with QO2; contains no evaluation logic. */
public sealed interface Expression {
    /** A non-null scalar constant. */
    record Literal(Value value) implements Expression {
        public Literal {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A non-null column reference resolved against the current row. */
    record Column(ColumnRef reference) implements Expression {
        public Column {
            Objects.requireNonNull(reference, "reference");
        }
    }

    /** Numeric operation with ordered, non-null operands. */
    record Arithmetic(Expression left, ArithmeticOp operator, Expression right)
            implements Expression {
        public Arithmetic {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    /** Required arithmetic operations. */
    enum ArithmeticOp {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE
    }
}
