package com.example.database.storage.catalog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog snapshot ↔ JSON bytes. Kept here so {@code PhysicalStorage} never parses JSON
 * and {@code CatalogManager} never knows the on-disk encoding.
 */
final class CatalogJson {

    private CatalogJson() {
    }

    static byte[] toBytes(TableMetadata table) {
        StringBuilder json = new StringBuilder();
        appendTable(json, table);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    static TableMetadata fromBytes(byte[] bytes, String database) {
        if (bytes.length == 0) {
            throw new CatalogException("empty catalog JSON");
        }
        return new Reader(new String(bytes, StandardCharsets.UTF_8)).readTable(database);
    }

    private static void appendTable(StringBuilder json, TableMetadata table) {
        json.append("{\"tableId\":").append(table.tableId().orElseThrow())
                .append(",\"name\":");
        appendString(json, table.name());
        json.append(",\"columns\":[");
        List<ColumnMetadata> columns = table.columns();
        for (int c = 0; c < columns.size(); c++) {
            if (c > 0) {
                json.append(',');
            }
            ColumnMetadata column = columns.get(c);
            json.append("{\"columnId\":").append(column.columnId().orElseThrow())
                    .append(",\"name\":");
            appendString(json, column.name());
            json.append(",\"type\":");
            appendString(json, column.type().name());
            json.append(",\"nullable\":").append(column.nullable()).append('}');
        }
        json.append("]}");
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

        private final String text;
        private int index;

        private Reader(String text) {
            this.text = text;
        }

        private TableMetadata readTable(String database) {
            Object root = value();
            skipWhitespace();
            if (index != text.length()) {
                throw new CatalogException("trailing content in catalog JSON");
            }
            return tableFrom(root, database);
        }

        private TableMetadata tableFrom(Object item, String database) {
            Map<String, Object> map = object(item, "table");
            int tableId = intField(map, "tableId");
            String name = stringField(map, "name");
            Object columnsValue = map.get("columns");
            if (!(columnsValue instanceof List<?> columnList)) {
                throw new CatalogException("table " + name + " missing columns array");
            }
            List<ColumnMetadata> columns = new ArrayList<>(columnList.size());
            for (Object column : columnList) {
                columns.add(columnFrom(column));
            }
            return new TableMetadata(tableId, database, name, columns);
        }

        private ColumnMetadata columnFrom(Object item) {
            Map<String, Object> map = object(item, "column");
            int columnId = intField(map, "columnId");
            String name = stringField(map, "name");
            String typeName = stringField(map, "type");
            ColumnType type;
            try {
                type = ColumnType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                throw new CatalogException("unknown column type: " + typeName, e);
            }
            Object nullableValue = map.get("nullable");
            if (!(nullableValue instanceof Boolean nullable)) {
                throw new CatalogException("column " + name + " missing nullable");
            }
            return new ColumnMetadata(columnId, name, type, nullable);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> object(Object item, String kind) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new CatalogException(kind + " must be a JSON object");
            }
            return (Map<String, Object>) map;
        }

        private static int intField(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (!(value instanceof Long number) || number != number.intValue()) {
                throw new CatalogException("catalog JSON missing integer " + key);
            }
            return number.intValue();
        }

        private static String stringField(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (!(value instanceof String string)) {
                throw new CatalogException("catalog JSON missing string " + key);
            }
            return string;
        }

        private Object value() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new CatalogException("unexpected end of catalog JSON");
            }
            char ch = text.charAt(index);
            if (ch == '{') {
                return object();
            }
            if (ch == '[') {
                return array();
            }
            if (ch == '"') {
                return string();
            }
            if (ch == 't' || ch == 'f') {
                return bool();
            }
            if (ch == '-' || Character.isDigit(ch)) {
                return number();
            }
            throw new CatalogException("unexpected catalog JSON at index " + index);
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = string();
                skipWhitespace();
                expect(':');
                map.put(key, value());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return list;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return value.toString();
                }
                if (ch == '\\') {
                    if (index >= text.length()) {
                        throw new CatalogException("unterminated escape in catalog JSON");
                    }
                    char escaped = text.charAt(index++);
                    value.append(switch (escaped) {
                        case '"', '\\' -> escaped;
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        default -> throw new CatalogException("unsupported escape \\" + escaped);
                    });
                } else {
                    value.append(ch);
                }
            }
            throw new CatalogException("unterminated string in catalog JSON");
        }

        private boolean bool() {
            if (text.startsWith("true", index)) {
                index += 4;
                return true;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return false;
            }
            throw new CatalogException("expected true or false in catalog JSON");
        }

        private Long number() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (index >= text.length() || !Character.isDigit(text.charAt(index))) {
                throw new CatalogException("expected number in catalog JSON");
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            return Long.parseLong(text.substring(start, index));
        }

        private void expect(char expected) {
            skipWhitespace();
            if (!peek(expected)) {
                throw new CatalogException("expected '" + expected + "' in catalog JSON");
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
