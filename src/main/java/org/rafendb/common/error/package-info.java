/**
 * Exception hierarchy. A component extends each root it needs once, passing its {@link
 * org.rafendb.common.error.Component}.
 *
 * <ul>
 *   <li>{@link org.rafendb.common.error.RafenDBRuntimeException} (unchecked): the client caused
 *       it. Shown to the user.
 *   <li>{@link org.rafendb.common.error.RafenDBInternalException} (checked): the client did not
 *       cause it. Handled by the receiving component, never shown to the user.
 * </ul>
 *
 * <p>Owner: Supergroup.
 */
package org.rafendb.common.error;
