package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.page.PageLayoutException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Order-preserving composite index key bytes for B+ tree separators and leaf lookups.
 * Null columns sort before non-null; typed encoding matches heap column rules.
 * <p>
 * Optional trailing zero padding ({@link #setKeyPaddingBytes(int)}) fattens on-disk
 * entries so pages fill sooner (taller trees in demos) without changing compare order
 * or decoded SQL values — search/insert still use the logical key only.
 */
public final class IndexKeyCodec {

    /** Process-wide pad length; set from {@code INDEX_KEY_PADDING_BYTES} / FileIndexStore. */
    private static volatile int keyPaddingBytes = 0;

    private IndexKeyCodec() {
    }

    /**
     * Trailing zero bytes appended after every encoded key (leaf + internal separators).
     * {@code 0} = production default. Changing this on a non-empty data dir requires
     * matching stamps on {@code .idx} meta pages (or recreating indexes).
     */
    public static void setKeyPaddingBytes(int paddingBytes) {
        if (paddingBytes < 0) {
            throw new IllegalArgumentException("index key padding must be >= 0, got " + paddingBytes);
        }
        if (paddingBytes > 0x8000) {
            throw new IllegalArgumentException("index key padding too large: " + paddingBytes);
        }
        keyPaddingBytes = paddingBytes;
    }

    /** Current trailing pad length used by {@link #encode} / stripped by {@link #decode}. */
    public static int keyPaddingBytes() {
        return keyPaddingBytes;
    }

    public static int encodedLength(Object[] values, ColumnType[] types) {
        validate(values, types);
        int length = nullBitmapBytes(types.length);
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) {
                continue;
            }
            length += typedByteLength(types[i], values[i]);
        }
        // Teaching knob: pad is part of on-disk entry size (fewer keys/page → taller tree).
        length += keyPaddingBytes;
        if (length > 0xFFFF) {
            throw new PageLayoutException("index key too large for u16: " + length);
        }
        return length;
    }

    public static byte[] encode(Object[] values, ColumnType[] types) {
        int length = encodedLength(values, types);
        ByteBuffer buf = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
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
        // ByteBuffer.allocate zero-fills the remainder — that IS the INDEX_KEY_PADDING_BYTES.
        // Compare/decode never look at those zeros; only slotted-page capacity cares.
        return buf.array();
    }

    public static Object[] decode(byte[] payload, ColumnType[] types) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(types, "types");
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        if (buf.remaining() < nullBitmapBytes(types.length)) {
            throw new PageLayoutException("index key truncated before null bitmap");
        }
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
        int pad = keyPaddingBytes;
        if (buf.remaining() == pad) {
            // Skip teaching pad so SQL sees the same values as with pad=0.
            buf.position(buf.limit());
        } else if (buf.hasRemaining()) {
            throw new PageLayoutException("index key has " + buf.remaining() + " trailing bytes");
        }
        return values;
    }

    /** Compare encoded keys for B+ tree ordering (logical columns only; pad ignored via decode). */
    public static int compare(byte[] left, byte[] right, ColumnType[] types) {
        Object[] leftValues = decode(left, types);
        Object[] rightValues = decode(right, types);
        for (int i = 0; i < types.length; i++) {
            int cmp = compareTyped(leftValues[i], rightValues[i], types[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * Compare only the first {@code prefixColumns} components (composite leading-prefix probes).
     */
    public static int comparePrefix(byte[] left, byte[] right, ColumnType[] types, int prefixColumns) {
        if (prefixColumns > types.length) {
            throw new PageLayoutException("prefixColumns exceeds key width: " + prefixColumns);
        }
        Object[] leftValues = decode(left, types);
        ColumnType[] prefixTypes = Arrays.copyOf(types, prefixColumns);
        Object[] rightValues = decode(right, prefixTypes);
        for (int i = 0; i < prefixColumns; i++) {
            int cmp = compareTyped(leftValues[i], rightValues[i], types[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** Encode only the first {@code prefixColumns} key components (still applies key padding). */
    public static byte[] encodePrefix(Object[] values, ColumnType[] types, int prefixColumns) {
        if (prefixColumns > types.length) {
            throw new PageLayoutException("prefixColumns exceeds key width: " + prefixColumns);
        }
        Object[] prefixValues = Arrays.copyOf(values, prefixColumns);
        ColumnType[] prefixTypes = Arrays.copyOf(types, prefixColumns);
        return encode(prefixValues, prefixTypes);
    }

    private static int compareTyped(Object left, Object right, ColumnType type) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return switch (type) {
            case INT -> Integer.compare(asInt(left), asInt(right));
            case BOOLEAN -> Boolean.compare(asBoolean(left), asBoolean(right));
            case VARCHAR -> asString(left).compareTo(asString(right));
        };
    }

    private static void validate(Object[] values, ColumnType[] types) {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(types, "types");
        if (values.length != types.length) {
            throw new PageLayoutException(
                    "value count " + values.length + " != key column count " + types.length);
        }
        if (types.length == 0) {
            throw new PageLayoutException("index key must have at least one column");
        }
    }

    private static int nullBitmapBytes(int columnCount) {
        return (columnCount + 7) / 8;
    }

    private static int typedByteLength(ColumnType type, Object value) {
        return switch (type) {
            case INT -> Integer.BYTES;
            case BOOLEAN -> 1;
            case VARCHAR -> {
                int utf8 = asString(value).getBytes(StandardCharsets.UTF_8).length;
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
            throw new PageLayoutException("index key truncated reading " + what);
        }
    }

    private static int asInt(Object value) {
        if (value instanceof Integer i) {
            return i;
        }
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
