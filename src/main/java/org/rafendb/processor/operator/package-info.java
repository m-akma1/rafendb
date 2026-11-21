/**
 * The operator contract shared by every QP operator. {@link Operator} is opened, read row by row with {@code next()}, and closed; it exposes the schema of the rows it returns. One operator object serves one execution and is not thread-safe. The operators themselves are in the subpackages.
 *
 * <p>Owner: G1 Query Processor (QP2).
 */
package org.rafendb.processor.operator;
