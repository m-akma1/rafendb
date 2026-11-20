/**
 * Wire protocol between client and server (QP-01, QP-02): typed messages, each a type byte, a
 * 4-byte length and a UTF-8 payload ({@link org.rafendb.net.MessageReader}, {@link
 * org.rafendb.net.MessageWriter}). The server greets with {@code HELLO}; the client sends one
 * statement per {@code QUERY}; the server answers {@code (RESULT | NOTICE)* ERROR? READY}, where
 * {@code READY} carries the session's {@link org.rafendb.net.TxState}. {@code CLOSING} may arrive
 * at any time when the server shuts down.
 *
 * <p>Owner: G1 Query Processor (QP1).
 */
package org.rafendb.net;
