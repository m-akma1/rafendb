package org.rafendb.processor.operator.join;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.type.Value;
import org.rafendb.processor.expr.DefaultExpressionEvaluator;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * An operator that performs a Block Nested Loop Join (BNLJ) between two input operators.
 *
 * <p>The left input is treated as the outer operator, while the right input is treated as the inner operator.
 * The operator buffers a block of outer rows and scans the entire inner input for each outer block.
 *
 * <p>The size of an outer block is determined by an estimated byte budget rather than a fixed number of rows.
 * If a single row exceeds the budget, it is still accepted when the block is empty to ensure forward progress.
 *
 * <p>The output schema consists of the outer schema followed by the inner schema.
 * Each output row is formed by concatenating the corresponding outer and inner rows.
 *
 * <p>If the join condition is {@code null}, every pair of rows matches, producing a Cartesian product.
 * Otherwise, only pairs for which the condition evaluates to {@code TRUE} are emitted.
 * Conditions evaluating to {@code FALSE} or {@code UNKNOWN} are rejected.
 *
 * <p>This operator is not thread-safe. Each instance belongs to a single query execution.
 */
public final class BnlJoinOp implements Operator {
    /**
     * Estimated fixed memory overhead of a row, in bytes.
     */
    private static final long ROW_OVERHEAD = 16L;

    /**
     * Estimated fixed memory overhead of each value in a row, in bytes.
     */
    private static final long VALUE_OVERHEAD = 8L;

    /**
     * Default estimated memory budget for an outer block (1 MiB).
     */
    private static final long DEFAULT_BYTE_BUDGET = 1024L * 1024L;

    private final Operator outer;

    private final Operator inner;

    private final Expr cond;

    private final ExpressionEvaluator evaluator;

    /**
     * Maximum estimated size of an outer block, in bytes.
     */
    private final long byteBudget;

    /**
     * Buffer containing the current outer block.
     */
    private final List<ResultRow> block = new ArrayList<>();

    /**
     * Outer row read from the child but not yet added to a block.
     */
    private ResultRow pendingOuterRow;

    /**
     * Current inner row being paired with the outer block.
     */
    private ResultRow innerRow;

    /**
     * Estimated size of the current outer block, in bytes.
     */
    private long usedBytes;

    /**
     * Position of the next outer row to pair with the current inner row.
     */
    private int outerPos;

    private final List<ColumnSchema> joinedSchema;

    private boolean outerExhausted;

    private boolean isOpen;

    public BnlJoinOp(Operator outer, Operator inner, Expr cond) {
        this(outer, inner, cond, new DefaultExpressionEvaluator(), DEFAULT_BYTE_BUDGET);
    }

    public BnlJoinOp(Operator outer, Operator inner, Expr cond, long byteBudget) {
        this(outer, inner, cond, new DefaultExpressionEvaluator(), byteBudget);
    }

    /**
     * Constructs a BNLJ operator with a custom evaluator and byte budget.
     *
     * @param outer the outer input operator; must not be {@code null}
     * @param inner the inner input operator; must not be {@code null}
     * @param cond the join predicate, or {@code null} for a Cartesian product
     * @param evaluator the evaluator used to test the join condition; must not be {@code null}
     * @param byteBudget maximum estimated size of an outer block, in bytes
     * @throws NullPointerException if {@code outer}, {@code inner}, or {@code evaluator} is {@code null}
     * @throws IllegalArgumentException if {@code byteBudget} is not positive
     */
    public BnlJoinOp(
            Operator outer,
            Operator inner,
            Expr cond,
            ExpressionEvaluator evaluator,
            long byteBudget) {
        this.outer = Objects.requireNonNull(outer, "outer");
        this.inner = Objects.requireNonNull(inner, "inner");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");

        if (byteBudget <= 0) {
            throw new IllegalArgumentException("byteBudget must be positive");
        }

        this.cond = cond;
        this.byteBudget = byteBudget;

        List<ColumnSchema> schema = new ArrayList<>();
        schema.addAll(outer.schema());
        schema.addAll(inner.schema());
        this.joinedSchema = List.copyOf(schema);
    }

    /**
     * Estimates the memory size of a row in bytes.
     *
     * <p>The estimate consists of an approximate object overhead and the payload size of each value.
     * Not an exact measurement of JVM heap usage.
     *
     * @param row the row to estimate
     * @return the estimated size in bytes
     */
    private long estimateSize(ResultRow row) {
        Objects.requireNonNull(row, "row");

        long size = ROW_OVERHEAD;
        for (Value value : row.values()) {
            size += VALUE_OVERHEAD;

            if (value.isNull()) continue;

            size +=
                    switch (value.type()) {
                        case INTEGER, FLOAT -> 4L;
                        case CHAR, VARCHAR ->
                                value.asText().getBytes(StandardCharsets.UTF_8).length;
                    };
        }

        return size;
    }

    /**
     * Loads the next block of rows from the outer operator.
     *
     * <p>The method reads rows until the next row would exceed the configured byte budget.
     * That row is retained in {@code pendingOuterRow} and will be used for the next block.
     *
     * <p>If the first row itself exceeds the budget, it is still included in the block.
     *
     * @return {@code true} if at least one row was loaded;
     *         {@code false} if the outer input is exhausted
     */
    private boolean loadOuterBlock() {
        block.clear();
        usedBytes = 0L;
        outerPos = 0;
        innerRow = null;

        while (!outerExhausted) {
            ResultRow row;

            if (pendingOuterRow != null) {
                row = pendingOuterRow;
                pendingOuterRow = null;
            } else {
                row = outer.next();
            }

            if (row == null) {
                outerExhausted = true;
                break;
            }

            long rowSize = estimateSize(row);

            // Check whether the next row fits. If not, save it for next block.
            // Safe from infinite loop, as a row is always allowed into an empty block.
            if (!block.isEmpty() && rowSize > byteBudget - usedBytes) {
                pendingOuterRow = row;
                break;
            }

            block.add(row);
            usedBytes += rowSize;
        }

        return !block.isEmpty();
    }

    /**
     * Determines whether a joined row satisfies the join condition.
     *
     * <p>If the condition is null, the pair automatically matches.
     * Otherwise, the joined result values evaluated using the expression evaluator.
     *
     * <p>The evaluator's test method returns true only when the condition evaluates to TRUE.
     *
     * @param joinedRow the concatenated outer and inner row
     * @return true if the pair satisfies the join condition
     */
    private boolean matches(ResultRow joinedRow) {
        if (cond == null) {
            return true;
        }

        return evaluator.test(cond, joinedRow, joinedSchema);
    }

    /**
     * Returns the output schema of this join operator.
     *
     * <p>The output schema consists of the outer schema followed
     * by the inner schema.
     *
     * @return the joined schema
     */
    @Override
    public List<ColumnSchema> schema() {
        return joinedSchema;
    }

    /**
     * Opens both child operators and initializes the join.
     *
     * <p>This method may be called repeatedly, even without a preceding close.
     * Both children are reopened so that their reading positions are reset.
     *
     * <p>The first outer block is loaded during this call.
     *
     * @throws RuntimeException if opening either child fails
     */
    @Override
    public void open() {
        isOpen = false;

        block.clear();
        pendingOuterRow = null;
        innerRow = null;
        usedBytes = 0L;
        outerPos = 0;
        outerExhausted = false;

        try {
            outer.open();
            inner.open();

            loadOuterBlock();

            isOpen = true;
        } catch (RuntimeException e) {
            try {
                close();
            } catch (RuntimeException closeError) {
                e.addSuppressed(closeError);
            }

            throw e;
        }
    }

    /**
     * Returns the next joined row.
     *
     * <p>For each inner row, this method iterates through every row in the current outer block.
     * When the inner input is exhausted, the next outer block is loaded and the inner operator is reopened from its beginning.
     *
     * <p>If the join condition is null, every pair is returned.
     * Otherwise, only pairs whose condition evaluates to TRUE are returned.
     *
     * @return the next joined result row, or {@code null} if no more matching pairs are available
     * @throws IllegalStateException if the operator is not open
     * @throws RuntimeException if a child operator or evaluator fails
     */
    @Override
    public ResultRow next() {
        if (!isOpen) {
            throw new IllegalStateException("BnlJoinOp is not open");
        }

        while (!block.isEmpty()) {
            if (innerRow == null) {
                innerRow = inner.next();

                if (innerRow == null) {
                    // The inner input is exhausted; load another outer block and rescan inner.
                    if (!loadOuterBlock()) {
                        return null;
                    }

                    inner.open();
                    continue;
                }

                outerPos = 0;
            }

            // Pair the current inner row with every row in the current outer block.
            while (outerPos < block.size()) {
                ResultRow outerRow = block.get(outerPos++);

                ResultRow joinedRow = ResultRow.concat(outerRow, innerRow);

                if (matches(joinedRow)) {
                    return joinedRow;
                }
            }
            // outerPos >= block.size(); all outer rows have been checked against the current inner
            // row.
            innerRow = null;
        }

        return null;
    }

    /**
     * Releases the block buffer and closes both child operators. Safe to call more than once, and
     * safe to call after {@link #next()} or a child has thrown an exception.
     */
    @Override
    public void close() {
        isOpen = false;

        block.clear();
        pendingOuterRow = null;
        innerRow = null;

        usedBytes = 0L;
        outerPos = 0;
        outerExhausted = true;

        RuntimeException failure = null;

        try {
            outer.close();
        } catch (RuntimeException e) {
            failure = e;
        }

        try {
            inner.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }

        if (failure != null) {
            throw failure;
        }
    }
}
