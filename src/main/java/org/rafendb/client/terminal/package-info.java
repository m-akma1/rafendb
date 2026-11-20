/**
 * Where the client reads input from: {@link org.rafendb.client.terminal.LineEditor}, implemented by
 * {@link org.rafendb.client.terminal.JLineEditor} for an interactive terminal (history,
 * multi-line editing, Ctrl+C/L/D) and {@link org.rafendb.client.terminal.ScriptInput} for files,
 * commands and pipes. The only package allowed to import JLine.
 *
 * <p>Owner: G1 Query Processor (QP1).
 */
package org.rafendb.client.terminal;
