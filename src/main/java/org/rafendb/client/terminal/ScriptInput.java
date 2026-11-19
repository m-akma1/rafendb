package org.rafendb.client.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Plain line input from a reader: a script file, a {@code --command} string or piped stdin. When
 * given a stream for prompts it also serves as a basic interactive input without line editing.
 */
public final class ScriptInput implements LineEditor {
    private final BufferedReader reader;
    private final PrintStream prompts;

    /**
     * Creates the input.
     *
     * @param reader where lines come from
     * @param prompts where prompts are printed, or {@code null} to print none
     */
    public ScriptInput(BufferedReader reader, PrintStream prompts) {
        this.reader = reader;
        this.prompts = prompts;
    }

    @Override
    public String readLine(String prompt) throws IOException {
        if (prompts != null && prompt != null) {
            prompts.print(prompt);
            prompts.flush();
        }
        return reader.readLine();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
