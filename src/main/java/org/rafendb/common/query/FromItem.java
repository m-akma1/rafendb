package org.rafendb.common.query;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.rafendb.common.expr.Expr;

public sealed interface FromItem {
    List<String> tables();

    private static List<String> concat(FromItem left, FromItem right) {
        return Stream.concat(left.tables().stream(), right.tables().stream()).toList();
    }

    /** A single table. */
    record TableRef(String name) implements FromItem {
        public TableRef {
            Objects.requireNonNull(name, "name must not be null");
        }

        @Override
        public List<String> tables() {
            return List.of(name);
        }
    }

    /** {@code left, right}: every row of left paired with every row of right. */
    record CartesianProduct(FromItem left, FromItem right) implements FromItem {
        public CartesianProduct {
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
        }

        @Override
        public List<String> tables() {
            return concat(left, right);
        }
    }

    /** {@code left JOIN right ON condition}. The condition is never null. */
    record Join(FromItem left, FromItem right, Expr condition) implements FromItem {
        public Join {
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
            Objects.requireNonNull(condition, "condition must not be null");
        }

        @Override
        public List<String> tables() {
            return concat(left, right);
        }
    }

    /** {@code left NATURAL JOIN right}. The condition is derived later by the Binder. */
    record NaturalJoin(FromItem left, FromItem right) implements FromItem {
        public NaturalJoin {
            Objects.requireNonNull(left, "left must not be null");
            Objects.requireNonNull(right, "right must not be null");
        }

        @Override
        public List<String> tables() {
            return concat(left, right);
        }
    }
}
