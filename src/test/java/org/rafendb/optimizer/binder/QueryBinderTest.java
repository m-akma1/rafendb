package org.rafendb.optimizer.binder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.schema.Column;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.optimizer.catalog.MockCatalog;

class QueryBinderTest {
    private final QueryBinder binder = new QueryBinder(MockCatalog.defaults());

    @Test
    void rejectsUnknownTableAndColumn() {
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                new SelectQuery(
                                        "", List.of(), List.of("missing"), null, null, List.of())));
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(new Expr.ColumnRef(null, "absent")),
                                        List.of("mahasiswa"),
                                        null,
                                        null,
                                        List.of())));
    }

    @Test
    void expandsStarAndResolvesCanonicalNames() {
        SelectQuery bound =
                (SelectQuery)
                        binder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(),
                                        List.of("MAHASISWA"),
                                        null,
                                        null,
                                        List.of()));
        assertEquals(4, bound.projection().size());
        assertEquals(new Expr.ColumnRef("mahasiswa", "nim"), bound.projection().get(0));
        assertEquals(bound, binder.bind(bound));
    }

    @Test
    void rejectsAmbiguousUnqualifiedColumn() {
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(new Expr.ColumnRef(null, "nama")),
                                        List.of("mahasiswa", "prodi"),
                                        null,
                                        null,
                                        List.of())));
    }

    @Test
    void validatesQualifiedColumnsAndTableQualifiedStar() {
        SelectQuery projection =
                (SelectQuery)
                        binder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(new Expr.ColumnRef("MAHASISWA", "*")),
                                        List.of("mahasiswa", "prodi"),
                                        null,
                                        null,
                                        List.of()));
        assertEquals(4, projection.projection().size());
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(new Expr.ColumnRef("absent", "nama")),
                                        List.of("mahasiswa"),
                                        null,
                                        null,
                                        List.of())));
    }

    @Test
    void rejectsNaturalJoinColumnsWithDifferentFamilies() {
        MockCatalog catalog =
                MockCatalog.builder()
                        .table("left_table")
                        .columns(new Column("id", DataType.INTEGER, 0, false, true))
                        .stats(2, 1, 4, 1024, java.util.Map.of("id", 2L))
                        .done()
                        .table("right_table")
                        .columns(new Column("id", DataType.VARCHAR, 8, false, true))
                        .stats(2, 1, 8, 512, java.util.Map.of("id", 2L))
                        .done()
                        .build();
        QueryBinder testBinder = new QueryBinder(catalog);
        assertThrows(
                ValidationException.class,
                () ->
                        testBinder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(),
                                        List.of("left_table", "right_table"),
                                        null,
                                        null,
                                        List.of()),
                                true));
    }

    @Test
    void assignsNullToTargetTypeAndChecksNullability() {
        InsertQuery nullable =
                new InsertQuery(
                        "",
                        "mahasiswa",
                        List.of(
                                new Expr.Literal(Value.ofInt(1)),
                                new Expr.Literal(Value.ofText(DataType.VARCHAR, "N")),
                                new Expr.Literal(Value.nullOf(DataType.INTEGER)),
                                new Expr.Literal(Value.ofText(DataType.CHAR, "IF"))));
        InsertQuery bound = (InsertQuery) binder.bind(nullable);
        assertEquals(DataType.FLOAT, ((Expr.Literal) bound.values().get(2)).value().type());
        InsertQuery notNull =
                new InsertQuery(
                        "",
                        "mahasiswa",
                        List.of(
                                new Expr.Literal(Value.nullOf(DataType.INTEGER)),
                                new Expr.Literal(Value.ofText(DataType.VARCHAR, "N")),
                                new Expr.Literal(Value.ofFloat(3.0f)),
                                new Expr.Literal(Value.ofText(DataType.CHAR, "IF"))));
        assertThrows(ValidationException.class, () -> binder.bind(notNull));
    }

    @Test
    void naturalJoinUsesSharedColumnsAndDeduplicatesStar() {
        SelectQuery natural =
                binder.bind(
                        new SelectQuery(
                                "",
                                List.of(),
                                List.of("mahasiswa", "prodi"),
                                null,
                                null,
                                List.of()),
                        true);
        assertNotNull(natural.joinCondition());
        assertEquals(5, natural.projection().size());
        assertEquals(natural, binder.bind(natural, true));
    }

    @Test
    void checksComparisonAndArithmeticTypes() {
        Expr bad =
                new Expr.Binary(
                        Expr.Op.EQ,
                        new Expr.ColumnRef(null, "nama"),
                        new Expr.Literal(Value.ofInt(3)));
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                new SelectQuery(
                                        "",
                                        List.of(),
                                        List.of("mahasiswa"),
                                        null,
                                        bad,
                                        List.of())));
        Expr arithmetic =
                new Expr.Binary(
                        Expr.Op.ADD,
                        new Expr.ColumnRef(null, "nama"),
                        new Expr.Literal(Value.ofInt(3)));
        assertThrows(
                ValidationException.class,
                () -> binder.bind(new UpdateQuery("", "mahasiswa", "nama", arithmetic, null)));
    }

    @Test
    void rejectsNonBooleanConditionsAndTypesNullFromContext() {
        SelectQuery nonBoolean =
                new SelectQuery(
                        "",
                        List.of(),
                        List.of("mahasiswa"),
                        null,
                        new Expr.ColumnRef(null, "nim"),
                        List.of());
        assertThrows(ValidationException.class, () -> binder.bind(nonBoolean));
        SelectQuery nullComparison =
                new SelectQuery(
                        "",
                        List.of(),
                        List.of("mahasiswa"),
                        null,
                        new Expr.Binary(
                                Expr.Op.EQ,
                                new Expr.ColumnRef(null, "nama"),
                                new Expr.Literal(Value.nullOf(DataType.INTEGER))),
                        List.of());
        Expr.Binary bound =
                (Expr.Binary) ((SelectQuery) binder.bind(nullComparison)).whereCondition();
        assertEquals(DataType.VARCHAR, ((Expr.Literal) bound.right()).value().type());
    }

    @Test
    void naturalJoinWithoutSharedColumnsRemainsCartesian() {
        MockCatalog catalog =
                MockCatalog.builder()
                        .table("first")
                        .columns(new Column("left_id", DataType.INTEGER, 0, false, false))
                        .stats(4, 1, 4, 1024, java.util.Map.of())
                        .done()
                        .table("second")
                        .columns(new Column("right_id", DataType.INTEGER, 0, false, false))
                        .stats(5, 1, 4, 1024, java.util.Map.of())
                        .done()
                        .build();
        SelectQuery query =
                new QueryBinder(catalog)
                        .bind(
                                new SelectQuery(
                                        "",
                                        List.of(),
                                        List.of("first", "second"),
                                        null,
                                        null,
                                        List.of()),
                                true);
        assertNull(query.joinCondition());
        assertEquals(2, query.projection().size());
    }

    @Test
    void validatesInsertArityAndWidening() {
        assertThrows(
                ValidationException.class,
                () -> binder.bind(new InsertQuery("", "mahasiswa", List.of())));
        InsertQuery bound =
                (InsertQuery)
                        binder.bind(
                                new InsertQuery(
                                        "",
                                        "mahasiswa",
                                        List.of(
                                                new Expr.Literal(Value.ofInt(1)),
                                                        new Expr.Literal(
                                                                Value.ofText(
                                                                        org.rafendb.common.type
                                                                                .DataType.VARCHAR,
                                                                        "A")),
                                                new Expr.Literal(Value.ofInt(4)),
                                                        new Expr.Literal(
                                                                Value.ofText(
                                                                        org.rafendb.common.type
                                                                                .DataType.CHAR,
                                                                        "CS")))));
        assertEquals(
                org.rafendb.common.type.DataType.FLOAT,
                ((Expr.Literal) bound.values().get(2)).value().type());
    }
}
