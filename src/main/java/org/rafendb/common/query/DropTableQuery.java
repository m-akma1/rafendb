package org.rafendb.common.query;

import java.util.Objects;

/** DROP TABLE with RESTRICT (also the default) or CASCADE. */
public record DropTableQuery(String originalSql, String table, boolean cascade)
        implements ParsedQuery {
    public DropTableQuery {
        Objects.requireNonNull(originalSql);
        Objects.requireNonNull(table);
    }
}
