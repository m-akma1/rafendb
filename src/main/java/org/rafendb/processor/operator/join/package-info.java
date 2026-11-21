/**
 * Joins (QP-08). {@link BnlJoinOp} is a block nested loop join: it buffers a block of outer rows and reads the inner input once per block. Cartesian product, JOIN ... ON and NATURAL JOIN all use it; they differ only in the join condition.
 *
 * <p>Owner: G1 Query Processor (QP4).
 */
package org.rafendb.processor.operator.join;
