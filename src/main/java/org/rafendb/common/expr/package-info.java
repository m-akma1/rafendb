/**
 * Expression tree (QO-04, QP-04). {@link Expr} is built by the QO parser for SELECT lists, WHERE, JOIN ... ON and SET, including IN, EXISTS and scalar subqueries. QO resolves column references to offsets, and QP evaluates the tree.
 *
 * <p>Owner: QO2, QP2.
 */
package org.rafendb.common.expr;
