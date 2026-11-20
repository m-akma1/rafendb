/**
 * Multi-client server (QP-02). {@link org.rafendb.server.ServerMain} listens on one port and serves
 * each client on its own thread with its own session from the one shared query processor, so
 * clients run concurrently and one leaving never disturbs another. Each connection answers every
 * statement with its rendered result ({@link org.rafendb.server.ResultRenderer}) or a readable
 * error, never a stack trace. The server stops gracefully on a client's {@code \shutdown}, Ctrl+C
 * or SIGTERM: open transactions are aborted and every session is closed.
 *
 * <p>Owner: G1 Query Processor (QP1).
 */
package org.rafendb.server;
