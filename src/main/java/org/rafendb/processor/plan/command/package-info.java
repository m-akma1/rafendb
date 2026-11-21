/**
 * Plan nodes for statements that change data or schema: INSERT, UPDATE, DELETE, CREATE TABLE, DROP TABLE, CREATE INDEX, and transaction commands. The optimizer plans SELECT only; the session builds these nodes itself.
 *
 * <p>Owner: G1 Query Processor.
 */
package org.rafendb.processor.plan.command;
