package org.rafendb.common.expr;

import java.util.Objects;

/** Proposed condition tree; booleans are not introduced as stored column values. */
public sealed interface Predicate {
    /** Comparison between two non-null expressions. */
    record Comparison(Expression left, ComparisonOp operator, Expression right)
            implements Predicate {
        public Comparison {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    /** Conjunction of two non-null predicates. */
    record And(Predicate left, Predicate right) implements Predicate {
        public And {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    /** SQL {@code =, <>, >, >=, <, <=} respectively. */
    enum ComparisonOp {
        EQ,
        NE,
        GT,
        GE,
        LT,
        LE
    }
}
