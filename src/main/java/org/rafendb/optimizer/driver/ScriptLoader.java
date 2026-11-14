package org.rafendb.optimizer.driver;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a QO-16 test script into {@link TestScenario}s. Decide the script format with your team
 * before implementing this; a simple one that needs no new parser:
 *
 * <pre>
 * # one scenario per non-blank, non-comment line
 * VALID            | SELECT * FROM student
 * SYNTAX_ERROR     | SELEC * FROM student
 * VALIDATION_ERROR | SELECT * FROM nosuchtable
 * </pre>
 *
 * <p>TODO: pick a delimiter that cannot appear in SQL (the pipe above should be safe, since it is
 * not one of QO-01's recognized tokens), split each line on the first delimiter, trim both sides,
 * and parse the left side with {@code ScenarioOutcome.valueOf(...)}. Name each scenario after its
 * line number, e.g. {@code "line 7"}, unless the format grows an explicit name column. Skip blank
 * lines and lines starting with {@code #}. A malformed line (bad outcome name, no delimiter) should
 * fail loudly (throw), since a silently-skipped scenario would hide a typo in the script rather
 * than a real optimizer bug.
 */
public final class ScriptLoader {
    private ScriptLoader() {}

    /**
     * Loads every scenario from a script file.
     *
     * @param path the script file
     * @return the scenarios, in file order
     * @throws IOException if the file cannot be read
     */
    public static List<TestScenario> load(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            int lineNumber = 1;
            List<TestScenario> scenarios = new ArrayList<>();
            while (line != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    line = reader.readLine();
                    lineNumber++;
                    continue;
                }
                String[] parts = trimmed.split("\\|", 2);
                if (parts.length != 2) {
                    throw new IOException("Malformed line " + lineNumber + ": " + trimmed);
                }
                ScenarioOutcome outcome = ScenarioOutcome.fromString(parts[0]);
                String sql = parts[1].trim();
                TestScenario scenario = new TestScenario("Line " + lineNumber, sql, outcome);
                scenarios.add(scenario);
                line = reader.readLine();
                lineNumber++;
            }
            return scenarios;
        } catch (IOException e) {
            throw new IOException("Failed to read script file: " + path, e);
        }
    }
}
