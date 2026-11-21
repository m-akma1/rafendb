/**
 * Text CLI client (QP-01). Reads statements that end with {@code ;}, possibly over several lines,
 * sends each one to the server and prints its result table, affected-row count, status or error.
 * Lines starting with {@code \} are meta-commands such as {@code \q}, {@code \f} and {@code
 * \connect}. Interactive on a terminal; with {@code --file} or {@code --command} it runs without
 * prompts and reports through its exit code. A lost connection keeps an interactive client running
 * until {@code \connect}.
 *
 * <p>Owner: G1 Query Processor (QP1).
 */
package org.rafendb.client;
