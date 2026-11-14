package org.rafendb.optimizer.binder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.rafendb.common.error.OptimizerRuntimeException.ValidationException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.InsertQuery;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.query.UpdateQuery;
import org.rafendb.common.schema.Column;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.mock.optimizer.catalog.MockCatalog;

class QueryBinderTest {
    private final QueryBinder binder = new QueryBinder(MockCatalog.defaults());

    private static SelectQuery select(List<Expr> projection, FromItem from, Expr where) {
        return new SelectQuery("", projection, from, where, List.of());
    }

    private static FromItem tables(String... names) {
        FromItem from = new FromItem.TableRef(names[0]);
        for (int i = 1; i < names.length; i++) {
            from = new FromItem.CartesianProduct(from, new FromItem.TableRef(names[i]));
        }
        return from;
    }

    @Test
    void rejectsUnknownTableAndColumn() {
        assertThrows(
                ValidationException.class,
                () -> binder.bind(select(List.of(), tables("missing"), null)));
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                select(
                                        List.of(new Expr.ColumnRef(null, "absent")),
                                        tables("mahasiswa"),
                                        null)));
    }

    @Test
    void expandsStarAndResolvesCanonicalNames() {
        SelectQuery bound =
                binder.bind(select(List.of(), new FromItem.TableRef("MAHASISWA"), null));
        assertEquals(4, bound.projection().size());
        assertEquals(new Expr.ColumnRef("mahasiswa", "nim", 0), bound.projection().get(0));
        assertEquals(bound, binder.bind(bound));
    }

    @Test
    void rejectsAmbiguousUnqualifiedColumn() {
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                select(
                                        List.of(new Expr.ColumnRef(null, "nama")),
                                        tables("mahasiswa", "prodi"),
                                        null)));
    }

    @Test
    void validatesQualifiedColumnsAndTableQualifiedStar() {
        SelectQuery projection =
                binder.bind(
                        select(
                                List.of(new Expr.ColumnRef("MAHASISWA", "*")),
                                tables("mahasiswa", "prodi"),
                                null));
        assertEquals(4, projection.projection().size());
        assertThrows(
                ValidationException.class,
                () ->
                        binder.bind(
                                select(
                                        List.of(new Expr.ColumnRef("absent", "nama")),
                                        tables("mahasiswa"),
                                        null)));
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
                                select(
                                        List.of(),
                                        new FromItem.NaturalJoin(
                                                new FromItem.TableRef("left_table"),
                                                new FromItem.TableRef("right_table")),
                                        null)));
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
                        select(
                                List.of(),
                                new FromItem.NaturalJoin(
                                        new FromItem.TableRef("mahasiswa"),
                                        new FromItem.TableRef("prodi")),
                                null));
        assertInstanceOf(FromItem.Join.class, natural.from());
        assertEquals(5, natural.projection().size());
        assertEquals(natural, binder.bind(natural));
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
                () -> binder.bind(select(List.of(), tables("mahasiswa"), bad)));
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
                select(List.of(), tables("mahasiswa"), new Expr.ColumnRef(null, "nim"));
        assertThrows(ValidationException.class, () -> binder.bind(nonBoolean));
        SelectQuery nullComparison =
                select(
                        List.of(),
                        tables("mahasiswa"),
                        new Expr.Binary(
                                Expr.Op.EQ,
                                new Expr.ColumnRef(null, "nama"),
                                new Expr.Literal(Value.nullOf(DataType.INTEGER))));
        Expr.Binary bound = (Expr.Binary) binder.bind(nullComparison).whereCondition();
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
                                select(
                                        List.of(),
                                        new FromItem.NaturalJoin(
                                                new FromItem.TableRef("first"),
                                                new FromItem.TableRef("second")),
                                        null));
        assertInstanceOf(FromItem.CartesianProduct.class, query.from());
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
