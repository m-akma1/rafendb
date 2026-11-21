/**
 * CREATE TABLE and DROP TABLE (QP-15, bonus). Both are command actions that change the catalog
 * through {@link org.rafendb.api.storage.StorageDefinition} when executed. SM does not log DDL, so
 * the session runs them only outside an explicit transaction.
 *
 * <p>{@link org.rafendb.processor.operator.ddl.DropTableOp} takes a WRITE lock on the table first.
 * {@link org.rafendb.processor.operator.ddl.CreateTableOp} takes no lock: the table does not exist
 * yet, so no other transaction can hold one.
 *
 * <p>Owner: G1 Query Processor.
 */
package org.rafendb.processor.operator.ddl;
