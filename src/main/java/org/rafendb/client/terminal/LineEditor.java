package org.rafendb.client.terminal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

/** Where the client reads its input from: a terminal, a script file or a single command. */
public interface LineEditor extends AutoCloseable {

    /**
     * Reads the next input. A terminal may return several lines at once, separated by {@code \n},
     * when the user edited a multi-line statement.
     *
     * @param prompt the prompt to show, or {@code null} for none; inputs that are not a terminal
     *     ignore it
     * @return the input without its final line terminator, or {@code null} at the end of input
     * @throws InterruptedIOException if the user cancelled the current input (Ctrl+C)
     * @throws IOException if reading fails
     */
    String readLine(String prompt) throws IOException;

    /**
     * Returns the inputs entered so far, oldest first. Empty for inputs without history.
     *
     * @return the history
     */
    default List<String> history() {
        return List.of();
    }

    /**
     * Shows text from another thread without breaking the line being typed, and changes the
     * prompt it is typed at. Inputs that are not a terminal just print the text.
     *
     * @param text the text, without a final line terminator
     * @param prompt the prompt to show from now on
     */
    default void printAbove(String text, String prompt) {
        System.out.println(text);
    }

    /**
     * Releases the input.
     *
     * @throws IOException if closing fails
     */
    @Override
    void close() throws IOException;
}
