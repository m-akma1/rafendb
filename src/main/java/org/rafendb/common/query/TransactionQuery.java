package org.rafendb.common.query;

import java.util.Objects;

/**
 * A transaction command: {@code BEGIN [TRANSACTION]}, {@code COMMIT}, or {@code ABORT} (QO-06).
 * Not optimized; forwarded to the Query Processor as is.
 *
 * @param originalSql the original SQL text
 * @param kind which command this is
 */
public record TransactionQuery(String originalSql, Kind kind) implements ParsedQuery {
    /** The transaction commands. */
    public enum Kind {
        BEGIN,
        COMMIT,
        ABORT
    }

    /** Rejects {@code null} components. */
    public TransactionQuery {
        Objects.requireNonNull(originalSql, "originalSql");
        Objects.requireNonNull(kind, "kind");
    }
}
