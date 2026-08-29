package com.example.database.storage.wal;

import com.example.database.storage.catalog.ColumnType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One JSON object per WAL line. Kept in the WAL package so {@code PhysicalStorage}
 * stays byte-only and the catalog does not know about the log encoding.
 */
final class WalJson {

    private WalJson() {
    }

    static byte[] toLine(WalRecord record) {
        StringBuilder json = new StringBuilder();
        json.append("{\"op\":");
        appendString(json, record.op().name());
        if (record.txnId() != null) {
            json.append(",\"txnId\":").append(record.txnId());
        }
        if (record.database() != null) {
            json.append(",\"database\":");
            appendString(json, record.database());
        }
        if (record.table() != null) {
            json.append(",\"table\":");
            appendString(json, record.table());
        }
        if (record.name() != null) {
            json.append(",\"name\":");
            appendString(json, record.name());
        }
        if (!record.columns().isEmpty()) {
            json.append(",\"columns\":[");
            List<WalRecord.ColumnPayload> columns = record.columns();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                WalRecord.ColumnPayload column = columns.get(i);
                json.append("{\"name\":");
                appendString(json, column.name());
                json.append(",\"type\":");
                appendString(json, column.type().name());
                json.append(",\"nullable\":").append(column.nullable()).append('}');
            }
            json.append(']');
        }
        if (!record.columnIds().isEmpty()) {
            json.append(",\"columnIds\":[");
            List<Integer> ids = record.columnIds();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(ids.get(i));
            }
            json.append(']');
        }
        json.append("}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static WalRecord fromLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new WalException("empty WAL line");
        }
        return new Reader(trimmed).readRecord();
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '"' || ch == '\\') {
                json.append('\\').append(ch);
            } else if (ch == '\n') {
                json.append("\\n");
            } else if (ch == '\r') {
                json.append("\\r");
            } else {
                json.append(ch);
            }
        }
        json.append('"');
    }

    private static final class Reader {
        private final String json;
        private int i;

        Reader(String json) {
            this.json = json;
        }

        WalRecord readRecord() {
            expect('{');
            WalOp op = null;
            Integer txnId = null;
            String database = null;
            String table = null;
            String name = null;
            List<WalRecord.ColumnPayload> columns = List.of();
            List<Integer> columnIds = List.of();
            while (true) {
                skipWs();
                if (peek() == '}') {
                    i++;
                    break;
                }
                if (op != null || txnId != null || database != null || table != null || name != null
                        || !columns.isEmpty() || !columnIds.isEmpty()) {
                    expect(',');
                }
                String key = readString();
                expect(':');
                switch (key) {
                    case "op" -> op = WalOp.valueOf(readString());
                    case "txnId" -> txnId = readInt();
                    case "database" -> database = readString();
                    case "table" -> table = readString();
                    case "name" -> name = readString();
                    case "columns" -> columns = readColumns();
                    case "columnIds" -> columnIds = readIntArray();
                    default -> throw new WalException("unknown WAL field: " + key);
                }
            }
            if (op == null) {
                throw new WalException("WAL record missing op");
            }
            return WalRecord.fromParsed(op, txnId, database, table, name, columns, columnIds);
        }

        private List<WalRecord.ColumnPayload> readColumns() {
            expect('[');
            List<WalRecord.ColumnPayload> columns = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return columns;
            }
            while (true) {
                columns.add(readColumn());
                skipWs();
                if (peek() == ']') {
                    i++;
                    return columns;
                }
                expect(',');
            }
        }

        private WalRecord.ColumnPayload readColumn() {
            expect('{');
            String name = null;
            ColumnType type = null;
            Boolean nullable = null;
            while (true) {
                skipWs();
                if (peek() == '}') {
                    i++;
                    break;
                }
                if (name != null || type != null || nullable != null) {
                    expect(',');
                }
                String key = readString();
                expect(':');
                switch (key) {
                    case "name" -> name = readString();
                    case "type" -> type = ColumnType.valueOf(readString());
                    case "nullable" -> nullable = readBoolean();
                    default -> throw new WalException("unknown column field: " + key);
                }
            }
            if (name == null || type == null || nullable == null) {
                throw new WalException("incomplete WAL column");
            }
            return new WalRecord.ColumnPayload(name, type, nullable);
        }

        private List<Integer> readIntArray() {
            expect('[');
            List<Integer> values = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return values;
            }
            while (true) {
                values.add(readInt());
                skipWs();
                if (peek() == ']') {
                    i++;
                    return values;
                }
                expect(',');
            }
        }

        private String readString() {
            skipWs();
            expect('"');
            StringBuilder value = new StringBuilder();
            while (i < json.length()) {
                char ch = json.charAt(i++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch == '\\') {
                    if (i >= json.length()) {
                        throw new WalException("truncated escape");
                    }
                    char esc = json.charAt(i++);
                    value.append(switch (esc) {
                        case '"', '\\' -> esc;
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        default -> throw new WalException("bad escape: \\" + esc);
                    });
                } else {
                    value.append(ch);
                }
            }
            throw new WalException("unclosed string");
        }

        private int readInt() {
            skipWs();
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < json.length() && Character.isDigit(json.charAt(i))) {
                i++;
            }
            if (start == i || (json.charAt(start) == '-' && start + 1 == i)) {
                throw new WalException("expected number");
            }
            return Integer.parseInt(json.substring(start, i));
        }

        private boolean readBoolean() {
            skipWs();
            if (json.startsWith("true", i)) {
                i += 4;
                return true;
            }
            if (json.startsWith("false", i)) {
                i += 5;
                return false;
            }
            throw new WalException("expected boolean");
        }

        private void expect(char expected) {
            skipWs();
            if (i >= json.length() || json.charAt(i) != expected) {
                throw new WalException("expected '" + expected + "'");
            }
            i++;
        }

        private char peek() {
            skipWs();
            if (i >= json.length()) {
                throw new WalException("unexpected end of WAL JSON");
            }
            return json.charAt(i);
        }

        private void skipWs() {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
        }
    }
}
