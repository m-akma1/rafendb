package org.rafendb.engine;

import java.util.Objects;
import org.rafendb.api.processor.QueryProcessor;
import org.rafendb.mock.processor.MockQueryProcessor;

/**
 * Composition root: builds the components, wires them together and hands the {@link
 * QueryProcessor} to the server. For now only the mock QP is wired; real components and {@code
 * --mock} flags are added here once they exist.
 */
public final class Engine {
    private final QueryProcessor processor;

    private Engine(QueryProcessor processor) {
        this.processor = Objects.requireNonNull(processor, "processor");
    }

    /**
     * Builds an engine backed by {@link MockQueryProcessor}.
     *
     * @return the engine
     */
    public static Engine withMocks() {
        return new Engine(new MockQueryProcessor());
    }

    /**
     * Returns the query processor clients talk to.
     *
     * @return the query processor, never {@code null}
     */
    public QueryProcessor processor() {
        return processor;
    }
}
