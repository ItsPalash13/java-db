package com.example.database.network.wire;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Hand-rolled JSON for the wire schema only — same trade-off as {@code CatalogJson}:
 * no third-party parser, fixed shapes, predictable bytes on the wire.
 */
public final class WireResponseJson {

    private WireResponseJson() {
    }

    public static byte[] toBytes(WireResponse response) {
        return toJson(response).getBytes(StandardCharsets.UTF_8);
    }

    public static String toJson(WireResponse response) {
        StringBuilder json = new StringBuilder();
        json.append("{\"v\":").append(response.version()).append(",\"messages\":[");
        List<WireMessage> messages = response.messages();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendMessage(json, messages.get(i));
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendMessage(StringBuilder json, WireMessage message) {
        if (message instanceof WireMessage.Error error) {
            json.append("{\"type\":\"ERROR\",\"message\":");
            appendString(json, error.message());
            json.append('}');
        } else if (message instanceof WireMessage.Ok ok) {
            json.append("{\"type\":\"OK\",\"rowsAffected\":").append(ok.rowsAffected()).append('}');
        } else if (message instanceof WireMessage.ResultSet resultSet) {
            json.append("{\"type\":\"RESULT_SET\",\"columns\":[");
            List<WireMessage.ResultSet.Column> columns = resultSet.columns();
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    json.append(',');
                }
                WireMessage.ResultSet.Column column = columns.get(c);
                json.append("{\"name\":");
                appendString(json, column.name());
                json.append(",\"type\":");
                appendString(json, column.type());
                json.append('}');
            }
            json.append("],\"rows\":[");
            List<List<Object>> rows = resultSet.rows();
            for (int r = 0; r < rows.size(); r++) {
                if (r > 0) {
                    json.append(',');
                }
                appendRow(json, rows.get(r));
            }
            json.append("]}");
        } else if (message instanceof WireMessage.Done done) {
            json.append("{\"type\":\"DONE\",\"rowsAffected\":").append(done.rowsAffected()).append('}');
        } else {
            throw new IllegalArgumentException("unknown wire message: " + message);
        }
    }

    private static void appendRow(StringBuilder json, List<Object> row) {
        json.append('[');
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendValue(json, row.get(i));
        }
        json.append(']');
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Boolean bool) {
            json.append(bool);
        } else if (value instanceof Number number) {
            json.append(number);
        } else {
            appendString(json, value.toString());
        }
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
            } else if (ch == '\t') {
                json.append("\\t");
            } else {
                json.append(ch);
            }
        }
        json.append('"');
    }
}
