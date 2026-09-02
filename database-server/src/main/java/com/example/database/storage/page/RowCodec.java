package com.example.database.storage.page;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.catalog.ColumnType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Packs / unpacks one row payload: {@code rowId} + null bitmap + typed column bytes.
 * <p>
 * Null bits omit the typed bytes (not a typed NULL sentinel). This is the on-page
 * format only — catalog schema still owns column names and nullability rules.
 * Big-endian so the same bytes round-trip across JVMs without relying on host order.
 */
public final class RowCodec {

    private RowCodec() {
    }

    public static int encodedLength(long rowId, Object[] values, ColumnType[] types) {
        validateSchema(values, types);
        int length = Long.BYTES + nullBitmapBytes(types.length);
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) {
                continue;
            }
            length += typedByteLength(types[i], values[i]);
        }
        // rowId occupies a fixed 8 bytes in every payload (see encode); the parameter
        // keeps this API aligned with encode so callers do not pass values twice.
        if (length < 0 || length > 0xFFFF) {
            throw new PageLayoutException("row payload too large for u16 slot length: " + length);
        }
        return length;
    }

    public static byte[] encode(long rowId, Object[] values, ColumnType[] types) {
        int length = encodedLength(rowId, values, types);
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(rowId);
        byte[] bitmap = new byte[nullBitmapBytes(types.length)];
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) {
                bitmap[i / 8] |= (byte) (1 << (i % 8));
            }
        }
        buf.put(bitmap);
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) {
                continue;
            }
            putTyped(buf, types[i], values[i]);
        }
        return buf.array();
    }

    public static Tuple decode(byte[] payload, ColumnType[] types) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(types, "types");
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        if (buf.remaining() < Long.BYTES + nullBitmapBytes(types.length)) {
            throw new PageLayoutException("row payload truncated before null bitmap");
        }
        long rowId = buf.getLong();
        byte[] bitmap = new byte[nullBitmapBytes(types.length)];
        buf.get(bitmap);
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            boolean isNull = (bitmap[i / 8] & (1 << (i % 8))) != 0;
            if (isNull) {
                values[i] = null;
            } else {
                values[i] = getTyped(buf, types[i]);
            }
        }
        if (buf.hasRemaining()) {
            throw new PageLayoutException("row payload has " + buf.remaining() + " trailing bytes");
        }
        return new Tuple(rowId, values);
    }

    private static void validateSchema(Object[] values, ColumnType[] types) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(types, "types");
        if (values.length != types.length) {
            throw new PageLayoutException(
                    "value count " + values.length + " != column count " + types.length);
        }
        if (types.length == 0) {
            throw new PageLayoutException("row must have at least one column");
        }
    }

    static int nullBitmapBytes(int columnCount) {
        return (columnCount + 7) / 8;
    }

    private static int typedByteLength(ColumnType type, Object value) {
        return switch (type) {
            case INT -> Integer.BYTES;
            case BOOLEAN -> 1;
            case VARCHAR -> {
                String text = asString(value);
                int utf8 = text.getBytes(StandardCharsets.UTF_8).length;
                if (utf8 > 0xFFFF) {
                    throw new PageLayoutException("VARCHAR exceeds u16 length: " + utf8);
                }
                yield Short.BYTES + utf8;
            }
        };
    }

    private static void putTyped(ByteBuffer buf, ColumnType type, Object value) {
        switch (type) {
            case INT -> buf.putInt(asInt(value));
            case BOOLEAN -> buf.put((byte) (asBoolean(value) ? 1 : 0));
            case VARCHAR -> {
                byte[] utf8 = asString(value).getBytes(StandardCharsets.UTF_8);
                buf.putShort((short) utf8.length);
                buf.put(utf8);
            }
        }
    }

    private static Object getTyped(ByteBuffer buf, ColumnType type) {
        return switch (type) {
            case INT -> {
                requireRemaining(buf, Integer.BYTES, "INT");
                yield buf.getInt();
            }
            case BOOLEAN -> {
                requireRemaining(buf, 1, "BOOLEAN");
                byte b = buf.get();
                if (b != 0 && b != 1) {
                    throw new PageLayoutException("BOOLEAN must be 0 or 1, got " + (b & 0xFF));
                }
                yield b == 1;
            }
            case VARCHAR -> {
                requireRemaining(buf, Short.BYTES, "VARCHAR length");
                int len = Short.toUnsignedInt(buf.getShort());
                requireRemaining(buf, len, "VARCHAR bytes");
                byte[] utf8 = new byte[len];
                buf.get(utf8);
                yield new String(utf8, StandardCharsets.UTF_8);
            }
        };
    }

    private static void requireRemaining(ByteBuffer buf, int need, String what) {
        if (buf.remaining() < need) {
            throw new PageLayoutException("row payload truncated reading " + what);
        }
    }

    private static int asInt(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
        // Analyser may leave a Long for integer literals; store as 32-bit INT.
        if (value instanceof Long l) {
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new PageLayoutException("INT out of 32-bit range: " + l);
            }
            return l.intValue();
        }
        throw new PageLayoutException("expected INT, got " + value.getClass().getSimpleName());
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new PageLayoutException("expected BOOLEAN, got " + value.getClass().getSimpleName());
    }

    private static String asString(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new PageLayoutException("expected VARCHAR, got " + value.getClass().getSimpleName());
    }
}
