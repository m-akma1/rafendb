package org.rafendb.client.terminal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jline.reader.CompletingParsedLine;
import org.jline.reader.EOFError;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Parser;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Interactive input on a terminal through JLine: line editing, a multi-line statement edited as one
 * block, history kept across runs (arrows keys, Ctrl+R), Ctrl+L to clear the screen, Ctrl+D to quit and
 * Ctrl+C to drop the current input. The only class that uses JLine.
 *
 * <p>Enter only finishes the input once {@code continuation} says it is complete; until then
 * JLine keeps the block open and shows the continuation prompt. Without a real terminal (an IDE
 * console, for example) JLine falls back to a basic one without key handling.
 */
public final class JLineEditor implements LineEditor {
    private final Terminal terminal;
    private final LineReader reader;

    /**
     * Opens the system terminal.
     *
     * @param continuation given everything typed so far, returns {@code null} if it can be sent as
     *     it is, or else the prompt for the next line
     * @param historyFile where history is kept across runs, or {@code null} to keep it for this
     *     run only
     * @param historySize how many entries to keep; 0 keeps none
     * @param onInterrupt runs when Ctrl+C is pressed while no input is being read, such as while a
     *     statement runs; runs on a signal thread
     * @throws IOException if no terminal can be opened
     */
    public JLineEditor(
            Function<String, String> continuation,
            Path historyFile,
            int historySize,
            Runnable onInterrupt)
            throws IOException {
        terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        terminal.handle(Terminal.Signal.INT, signal -> onInterrupt.run());
        LineReaderBuilder builder =
                LineReaderBuilder.builder()
                        .terminal(terminal)
                        .appName("rafendb")
                        .parser(new StatementParser(continuation))
                        .variable(LineReader.SECONDARY_PROMPT_PATTERN, "%M")
                        .variable(LineReader.HISTORY_SIZE, historySize)
                        .variable(LineReader.HISTORY_FILE_SIZE, historySize)
                        // SQL uses '!' (a != b); never expand it as a history reference.
                        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
        if (historyFile != null && historySize > 0) {
            builder.variable(LineReader.HISTORY_FILE, historyFile);
        }
        reader = builder.build();
    }

    @Override
    public String readLine(String prompt) throws IOException {
        try {
            return reader.readLine(prompt == null ? "" : prompt);
        } catch (UserInterruptException e) {
            throw new InterruptedIOException("input cancelled");
        } catch (EndOfFileException e) {
            return null;
        }
    }

    @Override
    public List<String> history() {
        List<String> lines = new ArrayList<>();
        for (History.Entry entry : reader.getHistory()) {
            lines.add(entry.line());
        }
        return lines;
    }

    @Override
    public void printAbove(String text, String prompt) {
        if (reader instanceof LineReaderImpl impl && impl.isReading()) {
            impl.setPrompt(prompt); // printAbove redraws the line with this prompt
        }
        reader.printAbove(text);
    }

    @Override
    public void close() throws IOException {
        try {
            reader.getHistory().save();
        } finally {
            terminal.close();
        }
    }

    /** Asks {@code continuation} whether Enter finishes the input; never splits into words. */
    private record StatementParser(Function<String, String> continuation) implements Parser {
        @Override
        public CompletingParsedLine parse(String line, int cursor, ParseContext context) {
            // ACCEPT_LINE decides whether Enter sends; SECONDARY_PROMPT asks which prompt each line
            // gets.
            if (context == ParseContext.ACCEPT_LINE || context == ParseContext.SECONDARY_PROMPT) {
                String next = continuation.apply(line);
                if (next != null) {
                    throw new EOFError(-1, cursor, "statement continues", next);
                }
            }
            return new WholeLine(line, cursor);
        }
    }

    /** The whole input as one word; the client has no completion to split words for. */
    private record WholeLine(String line, int cursor) implements CompletingParsedLine {
        @Override
        public String word() {
            return line;
        }

        @Override
        public int wordCursor() {
            return cursor;
        }

        @Override
        public int wordIndex() {
            return 0;
        }

        @Override
        public List<String> words() {
            return List.of(line);
        }

        @Override
        public CharSequence escape(CharSequence candidate, boolean complete) {
            return candidate;
        }

        @Override
        public int rawWordCursor() {
            return cursor;
        }

        @Override
        public int rawWordLength() {
            return line.length();
        }
    }
}
