/**
 * Exception hierarchy. A component extends each root it needs once, passing its {@link
 * org.rafendb.common.Component}.
 *
 * <ul>
 *   <li>{@link org.rafendb.common.error.RafenDBRuntimeException} (unchecked): the client caused
 *       it. Shown to the user.
 *   <li>{@link org.rafendb.common.error.RafenDBInternalException} (checked): the client did not
 *       cause it. Handled by the receiving component, never shown to the user.
 * </ul>
 *
 * <p>Both roots are {@code sealed}: their only direct subclasses are the component exceptions in this package, such as {@link org.rafendb.common.error.StorageRuntimeException} and {@link org.rafendb.common.error.RecoveryInternalException}. Those are {@code non-sealed}, so a component subclasses its own exception from its own package. To give a component a root it does not have yet, add {@code <Component>RuntimeException} or {@code <Component>InternalException} here and list it in that root's {@code permits} clause.
 *
 * <p>Owner: Supergroup.
 */
package org.rafendb.common.error;
