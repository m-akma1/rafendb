package org.rafendb.client.terminal;

import java.io.IOException;

/** Where the client reads its input from: a terminal, a script file or a single command. */
public interface LineEditor extends AutoCloseable {

    /**
     * Reads the next line.
     *
     * @param prompt the prompt to show, or {@code null} for none; inputs that are not a terminal
     *     ignore it
     * @return the line without its line terminator, or {@code null} at the end of input
     * @throws IOException if reading fails
     */
    String readLine(String prompt) throws IOException;

    /**
     * Releases the input.
     *
     * @throws IOException if closing fails
     */
    @Override
    void close() throws IOException;
}
