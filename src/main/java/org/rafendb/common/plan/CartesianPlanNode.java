package org.rafendb.common.plan;

import java.util.List;
import java.util.Objects;

/**
 * Describes a Cartesian product with the left child as the outer input and the right as the inner.
 *
 * <p>Instances are immutable and safe for concurrent reads.
 */
public final class CartesianPlanNode implements PlanNode {
    private final JoinAlgorithm algorithm;
    private final PlanNode left;
    private final PlanNode right;

    /**
     * Stores the algorithm and children in outer-to-inner order, without a join condition.
     *
     * @param algorithm the execution algorithm, must not be {@code null}
     * @param left the outer input, must not be {@code null}
     * @param right the inner input, must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CartesianPlanNode(JoinAlgorithm algorithm, PlanNode left, PlanNode right) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
    }

    /** @return the execution algorithm, never {@code null} */
    public JoinAlgorithm algorithm() {
        return algorithm;
    }

    /** @return the original outer input, never {@code null} */
    public PlanNode left() {
        return left;
    }

    /** @return the original inner input, never {@code null} */
    public PlanNode right() {
        return right;
    }

    /** {@inheritDoc} */
    @Override
    public List<PlanNode> children() {
        return List.of(left, right);
    }

    /** {@inheritDoc} */
    @Override
    public String describe() {
        return "CARTESIAN PRODUCT " + algorithm;
    }
}
