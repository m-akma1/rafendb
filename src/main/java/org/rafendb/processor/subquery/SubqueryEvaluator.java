package org.rafendb.processor.subquery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.query.FromItem;
import org.rafendb.common.query.OrderByItem;
import org.rafendb.common.query.SelectQuery;
import org.rafendb.common.type.Value;
import org.rafendb.processor.execution.ExecutionPlan;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.expr.TruthValue;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * Evaluator subquery di WHERE (QP-16 bonus). IN, EXISTS, sama scalar subquery diurus di sini,
 * sisanya dioper ke evaluator lain (delegate). Ekspresi tanpa subquery langsung ke delegate.
 *
 * <p>Kolom luar (korelasi) diganti Literal dari baris luar dulu, baru query dalam dijalanin.
 * Nilainya dicari pakai nama tabel + kolom, bukan offset. Query dalam dijalanin ulang tiap baris
 * luar, ga ada cache.
 *
 * <p>Runner harus diset lewat setRunner sebelum ketemu subquery. Ga thread-safe, satu instance buat
 * satu sesi (dibikin factory).
 */
public final class SubqueryEvaluator implements ExpressionEvaluator {
    private final ExpressionEvaluator delegate;
    private SubqueryRunner runner;

    /**
     * Bikin evaluator, runner-nya belum diset.
     *
     * @param delegate evaluator buat ekspresi tanpa subquery
     * @throws NullPointerException kalau delegate null
     */
    public SubqueryEvaluator(ExpressionEvaluator delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Set runner buat jalanin query dalam. Ga bisa lewat constructor soalnya runner butuh
     * translator, dan translator butuh factory yang megang evaluator ini.
     *
     * @throws NullPointerException kalau runner null
     */
    public void setRunner(SubqueryRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /** Scalar subquery diganti nilainya dulu, sisanya dihitung delegate. */
    @Override
    public Value evaluate(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        checkInput(expression, row, schema);
        return value(expression, row, schema);
    }

    /** AND, OR, NOT, IN, sama EXISTS diurus di sini, perbandingan dioper ke delegate. */
    @Override
    public TruthValue evaluateCondition(Expr condition, ResultRow row, List<ColumnSchema> schema) {
        checkInput(condition, row, schema);
        return condition(condition, row, schema);
    }

    /** Cek input kayak evaluator default, soalnya EXISTS ga pernah lewat delegate. */
    private static void checkInput(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(schema, "schema");
        if (row.size() != schema.size()) {
            throw new IllegalArgumentException("Row and schema sizes differ");
        }
    }

    private Value value(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        return delegate.evaluate(replaceScalars(expression, row, schema), row, schema);
    }

    /**
     * Logika tiga nilainya sama kayak evaluator default. AND: kiri FALSE, kanan ga dievaluasi.
     * OR: kiri TRUE, kanan ga dievaluasi. NOT cuma balik TRUE/FALSE, UNKNOWN tetap UNKNOWN.
     */
    private TruthValue condition(Expr condition, ResultRow row, List<ColumnSchema> schema) {
        if (!hasSubquery(condition)) {
            return delegate.evaluateCondition(condition, row, schema);
        }
        if (condition instanceof Expr.Binary binary && binary.op() == Expr.Op.AND) {
            TruthValue left = condition(binary.left(), row, schema);
            if (left == TruthValue.FALSE) {
                return TruthValue.FALSE;
            }
            TruthValue right = condition(binary.right(), row, schema);
            if (right == TruthValue.FALSE) {
                return TruthValue.FALSE;
            }
            if (left == TruthValue.UNKNOWN || right == TruthValue.UNKNOWN) {
                return TruthValue.UNKNOWN;
            }
            return TruthValue.TRUE;
        }
        if (condition instanceof Expr.Binary binary && binary.op() == Expr.Op.OR) {
            TruthValue left = condition(binary.left(), row, schema);
            if (left == TruthValue.TRUE) {
                return TruthValue.TRUE;
            }
            TruthValue right = condition(binary.right(), row, schema);
            if (right == TruthValue.TRUE) {
                return TruthValue.TRUE;
            }
            if (left == TruthValue.UNKNOWN || right == TruthValue.UNKNOWN) {
                return TruthValue.UNKNOWN;
            }
            return TruthValue.FALSE;
        }
        if (condition instanceof Expr.Not not) {
            TruthValue child = condition(not.child(), row, schema);
            if (child == TruthValue.TRUE) {
                return TruthValue.FALSE;
            }
            if (child == TruthValue.FALSE) {
                return TruthValue.TRUE;
            }
            return TruthValue.UNKNOWN;
        }
        if (condition instanceof Expr.ExistsSubquery subquery) {
            return exists(subquery, row, schema);
        }
        if (condition instanceof Expr.InSubquery subquery) {
            return in(subquery, row, schema);
        }
        Expr replaced = replaceScalars(condition, row, schema);
        return delegate.evaluateCondition(replaced, row, schema);
    }

    /** Cuma ngecek ekspresinya, ga masuk ke query di dalam subquery. */
    private static boolean hasSubquery(Expr expression) {
        if (expression instanceof Expr.Binary binary) {
            return hasSubquery(binary.left()) || hasSubquery(binary.right());
        }
        if (expression instanceof Expr.Not not) {
            return hasSubquery(not.child());
        }
        return expression instanceof Expr.ScalarSubquery
                || expression instanceof Expr.InSubquery
                || expression instanceof Expr.ExistsSubquery;
    }

    /** TRUE kalau query dalam ada barisnya. Cukup baca satu baris. */
    private TruthValue exists(
            Expr.ExistsSubquery subquery, ResultRow row, List<ColumnSchema> schema) {
        Operator operator = prepare(subquery.query(), row, schema).operator();
        try {
            operator.open();
            if (operator.next() != null) {
                return TruthValue.TRUE;
            }
            return TruthValue.FALSE;
        } finally {
            operator.close();
        }
    }

    /**
     * Nilai kiri dibandingin sama kolom pertama tiap baris hasil, pakai EQ punya delegate. Ketemu
     * TRUE langsung berhenti. Kalau ga ada yang cocok tapi sempet UNKNOWN (ada NULL), hasilnya
     * UNKNOWN.
     */
    private TruthValue in(Expr.InSubquery subquery, ResultRow row, List<ColumnSchema> schema) {
        Value left = value(subquery.value(), row, schema);
        Operator operator = prepare(subquery.query(), row, schema).operator();
        boolean unknown = false;
        try {
            operator.open();
            ResultRow result = operator.next();
            while (result != null) {
                Expr equals =
                        new Expr.Binary(
                                Expr.Op.EQ,
                                new Expr.Literal(left),
                                new Expr.Literal(result.get(0)));
                TruthValue match = delegate.evaluateCondition(equals, row, schema);
                if (match == TruthValue.TRUE) {
                    return TruthValue.TRUE;
                }
                if (match == TruthValue.UNKNOWN) {
                    unknown = true;
                }
                result = operator.next();
            }
        } finally {
            operator.close();
        }
        if (unknown) {
            return TruthValue.UNKNOWN;
        }
        return TruthValue.FALSE;
    }

    /** Tiap scalar subquery diganti Literal hasilnya. */
    private Expr replaceScalars(Expr expression, ResultRow row, List<ColumnSchema> schema) {
        if (expression instanceof Expr.ScalarSubquery subquery) {
            return new Expr.Literal(scalarValue(subquery.query(), row, schema));
        }
        if (expression instanceof Expr.Binary binary) {
            Expr left = replaceScalars(binary.left(), row, schema);
            Expr right = replaceScalars(binary.right(), row, schema);
            return new Expr.Binary(binary.op(), left, right);
        }
        if (expression instanceof Expr.Not not) {
            return new Expr.Not(replaceScalars(not.child(), row, schema));
        }
        return expression;
    }

    /**
     * Kolom pertama dari satu-satunya baris hasil. Hasil kosong jadi NULL (tipenya ikut kolom
     * pertama), lebih dari satu baris error.
     */
    private Value scalarValue(SelectQuery query, ResultRow row, List<ColumnSchema> schema) {
        ExecutionPlan.Rows rows = prepare(query, row, schema);
        Operator operator = rows.operator();
        try {
            operator.open();
            ResultRow first = operator.next();
            if (first == null) {
                return Value.nullOf(rows.columns().get(0).type());
            }
            if (operator.next() != null) {
                throw new IllegalStateException("Scalar subquery returned more than one row");
            }
            return first.get(0);
        } finally {
            operator.close();
        }
    }

    /** Isi kolom luar query dalam, terus minta operatornya ke runner. Operatornya belum dibuka. */
    private ExecutionPlan.Rows prepare(
            SelectQuery query, ResultRow row, List<ColumnSchema> schema) {
        if (runner == null) {
            throw new IllegalStateException("Subquery runner is not set");
        }
        SelectQuery filled = fillOuterQuery(query, 1, row, schema);
        return Objects.requireNonNull(runner.prepare(filled), "subquery rows");
    }

    /** Projection, FROM, WHERE, sama ORDER BY ditelusurin semua. */
    private SelectQuery fillOuterQuery(
            SelectQuery query, int level, ResultRow row, List<ColumnSchema> schema) {
        List<Expr> projection = new ArrayList<>();
        for (Expr expression : query.projection()) {
            projection.add(fillOuter(expression, level, row, schema));
        }
        List<OrderByItem> orderBy = null;
        if (query.orderBy() != null) {
            orderBy = new ArrayList<>();
            for (OrderByItem item : query.orderBy()) {
                Expr expression = fillOuter(item.expression(), level, row, schema);
                orderBy.add(new OrderByItem(expression, item.ascending()));
            }
        }
        return new SelectQuery(
                query.originalSql(),
                projection,
                fillOuterFrom(query.from(), level, row, schema),
                fillOuter(query.whereCondition(), level, row, schema),
                orderBy,
                query.limit());
    }

    /** Yang punya kondisi cuma Join, sisanya cuma ditelusurin anaknya. */
    private FromItem fillOuterFrom(
            FromItem from, int level, ResultRow row, List<ColumnSchema> schema) {
        if (from instanceof FromItem.Join join) {
            return new FromItem.Join(
                    fillOuterFrom(join.left(), level, row, schema),
                    fillOuterFrom(join.right(), level, row, schema),
                    fillOuter(join.condition(), level, row, schema));
        }
        if (from instanceof FromItem.CartesianProduct product) {
            return new FromItem.CartesianProduct(
                    fillOuterFrom(product.left(), level, row, schema),
                    fillOuterFrom(product.right(), level, row, schema));
        }
        if (from instanceof FromItem.NaturalJoin natural) {
            return new FromItem.NaturalJoin(
                    fillOuterFrom(natural.left(), level, row, schema),
                    fillOuterFrom(natural.right(), level, row, schema));
        }
        return from;
    }

    /**
     * Kolom yang scopeDepth-nya sama kayak level diganti Literal dari baris luar. Tiap masuk
     * subquery lagi, level nambah satu. Kolom yang nunjuk lebih jauh dari baris luar bikin error.
     * Null balik null (WHERE kosong).
     */
    private Expr fillOuter(Expr expression, int level, ResultRow row, List<ColumnSchema> schema) {
        if (expression instanceof Expr.ColumnRef column) {
            if (column.scopeDepth() > level) {
                throw new IllegalArgumentException("Correlated column is out of scope");
            }
            if (column.scopeDepth() == level) {
                return new Expr.Literal(outerValue(column, row, schema));
            }
            return column;
        }
        if (expression instanceof Expr.Binary binary) {
            return new Expr.Binary(
                    binary.op(),
                    fillOuter(binary.left(), level, row, schema),
                    fillOuter(binary.right(), level, row, schema));
        }
        if (expression instanceof Expr.Not not) {
            return new Expr.Not(fillOuter(not.child(), level, row, schema));
        }
        if (expression instanceof Expr.ScalarSubquery subquery) {
            return new Expr.ScalarSubquery(
                    fillOuterQuery(subquery.query(), level + 1, row, schema));
        }
        if (expression instanceof Expr.InSubquery subquery) {
            return new Expr.InSubquery(
                    fillOuter(subquery.value(), level, row, schema),
                    fillOuterQuery(subquery.query(), level + 1, row, schema));
        }
        if (expression instanceof Expr.ExistsSubquery subquery) {
            return new Expr.ExistsSubquery(
                    fillOuterQuery(subquery.query(), level + 1, row, schema));
        }
        return expression;
    }

    /**
     * Cari pakai nama tabel + kolom, ga peduli huruf besar kecil. Kolom tanpa nama tabel dianggap
     * ga ketemu.
     */
    private static Value outerValue(
            Expr.ColumnRef column, ResultRow row, List<ColumnSchema> schema) {
        if (column.table() != null) {
            for (int i = 0; i < schema.size(); i++) {
                Expr.ColumnRef reference = schema.get(i).reference();
                if (column.table().equalsIgnoreCase(reference.table())
                        && column.column().equalsIgnoreCase(reference.column())) {
                    return row.get(i);
                }
            }
        }
        throw new IllegalArgumentException(
                "Outer column not found: " + column.table() + "." + column.column());
    }
}
