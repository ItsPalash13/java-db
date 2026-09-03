package com.example.client.wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses only the wire JSON schema (not general JSON). Hand-rolled so the client module
 * needs no extra dependencies; shapes must stay aligned with server {@code WireResponseJson}.
 * SQL NULL is JSON {@code null} in RESULT_SET cells; those values are kept (not {@code List.copyOf}).
 */
public final class WireResponseJson {

    private WireResponseJson() {
    }

    public static WireResponse parse(String json) {
        return new Reader(json).readResponse();
    }

    private static final class Reader {

        private final String text;
        private int index;

        private Reader(String text) {
            this.text = text == null ? "" : text;
        }

        private WireResponse readResponse() {
            Map<String, Object> root = expectObject("response");
            int version = intField(root, "v");
            Object messagesValue = root.get("messages");
            if (!(messagesValue instanceof List<?> rawMessages)) {
                throw new WireParseException("response missing messages array");
            }
            List<WireMessage> messages = new ArrayList<>(rawMessages.size());
            for (Object item : rawMessages) {
                messages.add(messageFrom(expectMap(item, "message")));
            }
            skipWhitespace();
            if (index != text.length()) {
                throw new WireParseException("trailing content in wire JSON");
            }
            return new WireResponse(version, messages);
        }

        private WireMessage messageFrom(Map<String, Object> map) {
            String type = stringField(map, "type");
            if ("ERROR".equals(type)) {
                return new WireMessage.Error(stringField(map, "message"));
            }
            if ("OK".equals(type)) {
                return new WireMessage.Ok(intField(map, "rowsAffected"));
            }
            if ("RESULT_SET".equals(type)) {
                return resultSetFrom(map);
            }
            if ("DONE".equals(type)) {
                return new WireMessage.Done(intField(map, "rowsAffected"));
            }
            throw new WireParseException("unknown message type: " + type);
        }

        private WireMessage.ResultSet resultSetFrom(Map<String, Object> map) {
            Object columnsValue = map.get("columns");
            if (!(columnsValue instanceof List<?> rawColumns)) {
                throw new WireParseException("RESULT_SET missing columns array");
            }
            List<WireMessage.ResultSet.Column> columns = new ArrayList<>(rawColumns.size());
            for (Object item : rawColumns) {
                Map<String, Object> columnMap = expectMap(item, "column");
                columns.add(new WireMessage.ResultSet.Column(
                        stringField(columnMap, "name"),
                        stringField(columnMap, "type")
                ));
            }
            Object rowsValue = map.get("rows");
            if (!(rowsValue instanceof List<?> rawRows)) {
                throw new WireParseException("RESULT_SET missing rows array");
            }
            List<List<Object>> rows = new ArrayList<>(rawRows.size());
            for (Object rowItem : rawRows) {
                if (!(rowItem instanceof List<?> rawCells)) {
                    throw new WireParseException("RESULT_SET row must be an array");
                }
                rows.add(copyCellsAllowingNull(rawCells));
            }
            return new WireMessage.ResultSet(columns, rows);
        }

        /**
         * {@link List#copyOf} rejects null cells. Server RESULT_SET encodes SQL NULL as JSON
         * {@code null} (omitted INSERT columns, ADD COLUMN padding).
         */
        private static List<Object> copyCellsAllowingNull(List<?> rawCells) {
            List<Object> cells = new ArrayList<>(rawCells.size());
            for (Object cell : rawCells) {
                cells.add(cell);
            }
            return cells;
        }

        private Map<String, Object> expectObject(String kind) {
            skipWhitespace();
            Object value = value();
            return expectMap(value, kind);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> expectMap(Object value, String kind) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new WireParseException(kind + " must be a JSON object");
            }
            return (Map<String, Object>) map;
        }

        private static int intField(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (!(value instanceof Long number) || number != number.intValue()) {
                throw new WireParseException("wire JSON missing integer " + key);
            }
            return number.intValue();
        }

        private static String stringField(Map<String, Object> map, String key) {
            Object value = map.get(key);
            if (!(value instanceof String string)) {
                throw new WireParseException("wire JSON missing string " + key);
            }
            return string;
        }

        private Object value() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new WireParseException("unexpected end of wire JSON");
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
            if (ch == 'n') {
                return nullValue();
            }
            return number();
        }

        private Map<String, Object> object() {
            expectChar('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                String key = string();
                skipWhitespace();
                expectChar(':');
                map.put(key, value());
                skipWhitespace();
                if (peek() == '}') {
                    index++;
                    return map;
                }
                expectChar(',');
            }
        }

        private List<Object> array() {
            expectChar('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(value());
                skipWhitespace();
                if (peek() == ']') {
                    index++;
                    return list;
                }
                expectChar(',');
            }
        }

        private String string() {
            expectChar('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char ch = text.charAt(index++);
                if (ch == '"') {
                    return builder.toString();
                }
                if (ch == '\\') {
                    if (index >= text.length()) {
                        throw new WireParseException("unterminated string escape");
                    }
                    char escaped = text.charAt(index++);
                    if (escaped == '"' || escaped == '\\' || escaped == '/') {
                        builder.append(escaped);
                    } else if (escaped == 'b') {
                        builder.append('\b');
                    } else if (escaped == 'f') {
                        builder.append('\f');
                    } else if (escaped == 'n') {
                        builder.append('\n');
                    } else if (escaped == 'r') {
                        builder.append('\r');
                    } else if (escaped == 't') {
                        builder.append('\t');
                    } else {
                        throw new WireParseException("invalid string escape: \\" + escaped);
                    }
                } else {
                    builder.append(ch);
                }
            }
            throw new WireParseException("unterminated string");
        }

        private Object number() {
            int start = index;
            if (peek() == '-') {
                index++;
            }
            consumeDigits();
            if (index < text.length() && text.charAt(index) == '.') {
                index++;
                consumeDigits();
            }
            String literal = text.substring(start, index);
            try {
                if (literal.contains(".")) {
                    return Double.parseDouble(literal);
                }
                return Long.parseLong(literal);
            } catch (NumberFormatException e) {
                throw new WireParseException("invalid number: " + literal, e);
            }
        }

        private void consumeDigits() {
            if (index >= text.length() || !Character.isDigit(text.charAt(index))) {
                throw new WireParseException("expected digit in number");
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
        }

        private Object bool() {
            if (text.startsWith("true", index)) {
                index += 4;
                return Boolean.TRUE;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new WireParseException("invalid boolean at index " + index);
        }

        private Object nullValue() {
            if (text.startsWith("null", index)) {
                index += 4;
                return null;
            }
            throw new WireParseException("invalid null at index " + index);
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char ch = text.charAt(index);
                if (ch == ' ' || ch == '\n' || ch == '\r' || ch == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private char peek() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new WireParseException("unexpected end of wire JSON");
            }
            return text.charAt(index);
        }

        private void expectChar(char expected) {
            skipWhitespace();
            if (index >= text.length() || text.charAt(index) != expected) {
                throw new WireParseException("expected '" + expected + "' at index " + index);
            }
            index++;
        }
    }
}
