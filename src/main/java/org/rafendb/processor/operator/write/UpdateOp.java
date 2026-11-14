package org.rafendb.processor.operator.write;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.rafendb.api.storage.StorageManager;
import org.rafendb.common.error.StorageInternalException;
import org.rafendb.common.error.StorageRuntimeException;
import org.rafendb.common.expr.Expr;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Rid;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.tuple.RowImageCodec;
import org.rafendb.common.txn.ChangeRecord;
import org.rafendb.common.txn.TxId;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.processor.api.ExecutionContext;
import org.rafendb.processor.expr.ExpressionEvaluator;
import org.rafendb.processor.operator.Operator;
import org.rafendb.processor.schema.ColumnSchema;
import org.rafendb.processor.tuple.ResultRow;

/**
 * UPDATE (QP-11): sets one column on every row produced by {@code child}, inside the session's
 * current transaction.
 *
 * <p>Phases:
 *
 * <ol>
 *   <li>WRITE access to the table, <em>before</em> reading (CCM locks whole tables).
 *   <li>Eager collection (Halloween problem): drain {@code child}, compute every new row, check
 *       it against the column, encode its log images, and for every row whose record would grow
 *       reserve a new Rid. Nothing is logged or written yet, so every failure we can foresee
 *       (evaluation error, NOT NULL, text too long, row too large, storage full) leaves the table
 *       and the transaction untouched.
 *   <li>Per row, in place: log UPDATE, then {@link StorageManager#update}. A record that would
 *       grow is relocated instead: log DELETE and delete the old Rid, then log INSERT and insert
 *       at the reserved Rid (the Rid changes, as the log requires for a relocation).
 * </ol>
 *
 * <p>A failure after a record was logged aborts the whole transaction: a statement has no
 * rollback of its own, and the log must not promise a change that was never applied.
 *
 * <p>The child must yield full table rows with their RID (Filter over Scan). It is opened and
 * closed here. Does not begin or commit. One instance, one execution.
 */
public final class UpdateOp implements DMLOperator {
    /**
     * What a non-NULL VARCHAR adds to a serialized record besides its text bytes: a 2-byte offset
     * table entry plus a 2-byte length prefix. Mirrors {@code RecordSerializer}; pinned by
     * {@code UpdateSizingCompatibilityTest}.
     */
    static final int VARCHAR_OVERHEAD = 4;

    private final String table;
    private final Expr.ColumnRef column;
    private final Expr valueExpr;
    private final Operator child;
    private final ExpressionEvaluator evaluator;
    private final ExecutionContext ctx;
    private final WriteSupport support;

    /**
     * One target row. In place: {@code fresh} is null and {@code first} is the UPDATE record.
     * Relocated: {@code fresh} is the reserved Rid, {@code first} is the DELETE at {@code rid}
     * and {@code second} the INSERT at {@code fresh}.
     */
    private record Change(Rid rid, Row after, Rid fresh, ChangeRecord first, ChangeRecord second) {}

    public UpdateOp(
            String table,
            Expr.ColumnRef column,
            Expr valueExpr,
            Operator child,
            ExpressionEvaluator evaluator,
            ExecutionContext ctx) {
        this(table, column, valueExpr, child, evaluator, ctx, RowImageCodec.DEFAULT);
    }

    public UpdateOp(
            String table,
            Expr.ColumnRef column,
            Expr valueExpr,
            Operator child,
            ExpressionEvaluator evaluator,
            ExecutionContext ctx,
            RowImageCodec codec) {
        this.table = Objects.requireNonNull(table, "table");
        this.column = Objects.requireNonNull(column, "column");
        this.valueExpr = Objects.requireNonNull(valueExpr, "valueExpr");
        this.child = Objects.requireNonNull(child, "child");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.support = new WriteSupport(ctx, codec);
    }

    /** @return the number of rows that matched and were updated */
    @Override
    public Long execute() {
        TxId tx = support.requireTx();
        support.acquireWrite(tx, table);
        StorageManager sm = ctx.getStorageManager();

        List<Change> changes = collect(sm);

        int done = 0;
        try {
            for (Change c : changes) {
                if (c.fresh() == null) {
                    updateInPlace(tx, sm, c);
                } else {
                    relocate(tx, sm, c);
                }
                done++;
            }
        } finally {
            // Reservations of rows never reached (or whose relocation failed) are given back.
            // cancelReservation is idempotent and never deletes a live record.
            for (int i = done; i < changes.size(); i++) {
                cancelQuietly(sm, changes.get(i).fresh());
            }
        }
        return (long) changes.size();
    }

    private void updateInPlace(TxId tx, StorageManager sm, Change c) {
        long lsn = support.log(tx, c.first());
        try {
            sm.update(table, c.rid(), c.after(), lsn);
        } catch (StorageInternalException | RuntimeException e) {
            throw support.abortAfterLoggedFailure(tx, "updating " + table + " at " + c.rid(), e);
        }
    }

    /** DELETE at the old Rid, then INSERT at the Rid reserved in {@link #collect}. */
    private void relocate(TxId tx, StorageManager sm, Change c) {
        long deleteLsn = support.log(tx, c.first());
        try {
            if (!sm.delete(table, c.rid(), deleteLsn)) {
                throw new IllegalStateException("No live record at " + c.rid() + " to relocate");
            }
        } catch (StorageInternalException | RuntimeException e) {
            cancelQuietly(sm, c.fresh()); // free the slot first, then drop the transaction
            throw support.abortAfterLoggedFailure(
                    tx, "deleting from " + table + " at " + c.rid(), e);
        }

        long insertLsn = support.log(tx, c.second());
        try {
            sm.insert(table, c.fresh(), c.after(), insertLsn);
        } catch (StorageInternalException | RuntimeException e) {
            cancelQuietly(sm, c.fresh());
            throw support.abortAfterLoggedFailure(
                    tx, "inserting the relocated row of " + table + " at " + c.fresh(), e);
        }
    }

    /**
     * Reads all targets and prepares every write before any of them happens. On failure the
     * reservations made so far are released.
     */
    private List<Change> collect(StorageManager sm) {
        TableSchema tableSchema = sm.getSchema(table);
        List<Change> changes = new ArrayList<>();
        try {
            child.open();
            List<ColumnSchema> schema = child.schema();
            if (schema.size() != tableSchema.columns().size()) {
                throw new IllegalStateException(
                        "UPDATE child must yield full rows of "
                                + table
                                + ": expected "
                                + tableSchema.columns().size()
                                + " columns, got "
                                + schema.size());
            }
            int target = resolveTarget(schema);
            DataType targetType = schema.get(target).type();
            Column targetColumn = tableSchema.columns().get(target);

            ResultRow old;
            while ((old = child.next()) != null) {
                if (old.rid() == null) {
                    throw new IllegalStateException("UPDATE child produced a row without RID");
                }
                Value oldValue = old.values().get(target);
                Value computed = evaluator.evaluate(valueExpr, old, schema);
                Value newValue = coerce(computed, targetType);

                // Same check SM runs in update(); doing it here keeps the failure before any log.
                if (!targetColumn.accepts(newValue)) {
                    throw new StorageRuntimeException(
                            "value "
                                    + newValue
                                    + " is not acceptable for "
                                    + table
                                    + "."
                                    + targetColumn.name());
                }

                List<Value> newValues = new ArrayList<>(old.values());
                newValues.set(target, newValue);
                Row before = new Row(old.values());
                Row after = new Row(newValues);

                if (growsRecord(oldValue, newValue, targetColumn)) {
                    Rid fresh = reserve(sm, after);
                    try {
                        changes.add(
                                new Change(
                                        old.rid(),
                                        after,
                                        fresh,
                                        support.deleteChange(table, old.rid(), before),
                                        support.insertChange(table, fresh, after)));
                    } catch (RuntimeException e) {
                        cancelQuietly(sm, fresh); // e.g. the row could not be encoded
                        throw e;
                    }
                } else {
                    changes.add(
                            new Change(
                                    old.rid(),
                                    after,
                                    null,
                                    support.updateChange(table, old.rid(), before, after),
                                    null));
                }
            }
        } catch (RuntimeException e) {
            for (Change c : changes) {
                cancelQuietly(sm, c.fresh());
            }
            throw e;
        } finally {
            child.close();
        }
        return changes;
    }

    /**
     * Reserves the slot a relocated row will move to. Too large for one block surfaces as
     * StorageRuntimeException (a user error); a full or failing disk as an internal failure.
     * Either way nothing has been logged or changed yet.
     */
    private Rid reserve(StorageManager sm, Row after) {
        try {
            return sm.reserveRid(table, after);
        } catch (StorageInternalException e) {
            throw support.storageFailure(
                    "reserving a slot in " + table + " for a relocated row", e);
        }
    }

    private void cancelQuietly(StorageManager sm, Rid rid) {
        if (rid == null) return;
        try {
            sm.cancelReservation(table, rid);
        } catch (StorageInternalException | RuntimeException ignored) {
            // The original failure is more useful than this one.
        }
    }

    /**
     * Whether the serialized record gets bigger, in which case it may not fit at the same Rid.
     * A record that does not grow always fits: SlottedPage writes it into the slot's capacity.
     *
     * <p>TODO(team): replace by an exact SM pre-check (e.g. {@code canUpdateInPlace}) once SM1
     * offers one. This estimate relocates every growing row, even if its page had room.
     */
    static boolean growsRecord(Value before, Value after, Column column) {
        return storedSize(after, column) > storedSize(before, column);
    }

    /**
     * The only part of a record this column can change: a non-NULL VARCHAR costs its overhead
     * plus its UTF-8 bytes. INTEGER, FLOAT and CHAR are fixed-size (NULL included).
     */
    static int storedSize(Value v, Column column) {
        if (column.type() != DataType.VARCHAR || v.isNull()) {
            return 0;
        }
        return VARCHAR_OVERHEAD + v.asText().getBytes(StandardCharsets.UTF_8).length;
    }

    /** Uses the binder's offset when set, otherwise looks the column up by name. */
    private int resolveTarget(List<ColumnSchema> schema) {
        if (column.offset() >= 0 && column.offset() < schema.size()) {
            return column.offset();
        }
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).reference().column().equalsIgnoreCase(column.column())) return i;
        }
        throw new IllegalArgumentException(
                "Unknown column " + column.column() + " in table " + table);
    }

    /**
     * Lets the new value match the column type where the conversion is obvious (e.g. {@code salary
     * = 1.05 * salary} yields FLOAT). Anything else is rejected by the column check in collect.
     */
    private static Value coerce(Value v, DataType target) {
        if (v.type() == target) return v;
        if (v.isNull()) return Value.nullOf(target);
        if (target == DataType.FLOAT && v.type() == DataType.INTEGER) {
            return Value.ofFloat(v.asInt());
        }
        if (target == DataType.INTEGER && v.type() == DataType.FLOAT) {
            return Value.ofInt(Math.round(v.asFloat())); // TODO(team): round or reject?
        }
        if (target.isText() && v.type().isText()) return Value.ofText(target, v.asText());
        return v;
    }
}
