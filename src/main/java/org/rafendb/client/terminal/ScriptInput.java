package org.rafendb.client.terminal;

import java.io.BufferedReader;
import java.io.IOException;

/** Plain line input without prompts: a script file, a {@code --command} string or piped stdin. */
public final class ScriptInput implements LineEditor {
    private final BufferedReader reader;

    /**
     * Creates the input.
     *
     * @param reader where lines come from
     */
    public ScriptInput(BufferedReader reader) {
        this.reader = reader;
    }

    @Override
    public String readLine(String prompt) throws IOException {
        return reader.readLine();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
