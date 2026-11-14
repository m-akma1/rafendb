package org.rafendb.processor.operator.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.rafendb.common.schema.Column;
import org.rafendb.common.schema.TableSchema;
import org.rafendb.common.tuple.Row;
import org.rafendb.common.type.DataType;
import org.rafendb.common.type.Value;
import org.rafendb.storage.record.RecordSerializer;

/** Pins UpdateOp's size estimate to what RecordSerializer really produces. */
class UpdateSizingCompatibilityTest {
    private static final TableSchema SCHEMA =
        new TableSchema(
            "t",
            List.of(
                new Column("id", DataType.INTEGER, 0, false, true),
                new Column("name", DataType.VARCHAR, 200, true, false),
                new Column("code", DataType.CHAR, 8, true, false),
                new Column("tag", DataType.VARCHAR, 50, true, false),
                new Column("score", DataType.FLOAT, 0, true, false)),
            List.of());

    private static final String[] PIECES = {"a", "b", "z", " ", ";", "\u00e9", "\u4e2d", "\uD83D\uDE00"};

    private final RecordSerializer serializer = new RecordSerializer();

    private static Value randomValue(Random rnd, Column col) {
        if (col.nullable() && rnd.nextInt(5) == 0) {
            return Value.nullOf(col.type());
        }
        return switch (col.type()) {
            case INTEGER -> Value.ofInt(rnd.nextInt());
            case FLOAT -> Value.ofFloat(rnd.nextFloat());
            case CHAR, VARCHAR -> {
                int limit = Math.min(col.length(), 1 + rnd.nextInt(60));
                StringBuilder sb = new StringBuilder();
                int bytes = 0;
                for (int i = 0; i < 40; i++) {
                    String p = PIECES[rnd.nextInt(PIECES.length)];
                    int len = p.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes + len > limit) break;
                    sb.append(p);
                    bytes += len;
                }
                yield Value.ofText(col.type(), sb.toString());
            }
        };
    }

    @Test
    void estimateMatchesRecordSerializerForEverySingleColumnChange() {
        Random rnd = new Random(42);
        List<Column> columns = SCHEMA.columns();
        for (int n = 0; n < 5000; n++) {
            List<Value> base = new ArrayList<>();
            for (Column c : columns) base.add(randomValue(rnd, c));

            int target = rnd.nextInt(columns.size());
            Column col = columns.get(target);
            Value oldValue = base.get(target);
            Value newValue = randomValue(rnd, col);

            List<Value> changed = new ArrayList<>(base);
            changed.set(target, newValue);

            int delta =
                serializer.encodedSize(new Row(changed), SCHEMA)
                    - serializer.encodedSize(new Row(base), SCHEMA);

            assertEquals(
                UpdateOp.storedSize(newValue, col) - UpdateOp.storedSize(oldValue, col),
                delta,
                "size delta for column " + col.name() + ": " + oldValue + " -> " + newValue);
            if (!UpdateOp.growsRecord(oldValue, newValue, col)) {
                assertTrue(delta <= 0, "a non-growing update must never need more space");
            }
        }
    }
}
