package com.example.client.wire;

import java.io.PrintStream;
import java.util.List;

/**
 * Turns structured {@link WireResponse} messages into human-readable console output.
 * Separated from {@link com.example.client.DatabaseClient} so transport and rendering
 * can evolve independently (e.g. GUI client reuses decode, not ASCII tables).
 */
public final class ResponsePrinter {

    private final PrintStream out;

    public ResponsePrinter(PrintStream out) {
        this.out = out;
    }

    public void print(WireResponse response) {
        for (WireMessage message : response.messages()) {
            printMessage(message);
        }
    }

    private void printMessage(WireMessage message) {
        if (message instanceof WireMessage.Error error) {
            out.println(error.message());
        } else if (message instanceof WireMessage.Ok ok) {
            if (ok.rowsAffected() > 0) {
                out.println("OK (" + ok.rowsAffected() + " rows affected)");
            } else {
                out.println("OK");
            }
        } else if (message instanceof WireMessage.ResultSet resultSet) {
            printResultSet(resultSet);
        } else if (message instanceof WireMessage.Done done) {
            if (done.rowsAffected() > 0) {
                out.println("(" + done.rowsAffected() + " rows)");
            }
        }
    }

    private void printResultSet(WireMessage.ResultSet resultSet) {
        List<WireMessage.ResultSet.Column> columns = resultSet.columns();
        if (columns.isEmpty()) {
            out.println("(empty result set)");
            return;
        }
        int[] widths = new int[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            widths[c] = columns.get(c).name().length();
        }
        List<List<Object>> rows = resultSet.rows();
        for (List<Object> row : rows) {
            for (int c = 0; c < columns.size(); c++) {
                String cell = formatCell(c < row.size() ? row.get(c) : null);
                widths[c] = Math.max(widths[c], cell.length());
            }
        }
        printSeparator(columns, widths);
        printRow(columns.stream().map(WireMessage.ResultSet.Column::name).toList(), widths);
        printRule(widths);
        for (List<Object> row : rows) {
            printRow(row, widths);
        }
        out.println("(" + rows.size() + " rows)");
    }

    private void printSeparator(List<WireMessage.ResultSet.Column> columns, int[] widths) {
        for (int c = 0; c < columns.size(); c++) {
            if (c > 0) {
                out.print(" | ");
            }
            out.print(pad(columns.get(c).name(), widths[c]));
        }
        out.println();
    }

    private void printRow(List<?> cells, int[] widths) {
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                out.print(" | ");
            }
            Object value = c < cells.size() ? cells.get(c) : null;
            out.print(pad(formatCell(value), widths[c]));
        }
        out.println();
    }

    private void printRule(int[] widths) {
        for (int c = 0; c < widths.length; c++) {
            if (c > 0) {
                out.print("-+-");
            }
            out.print("-".repeat(widths[c]));
        }
        out.println();
    }

    private static String formatCell(Object value) {
        return value == null ? "NULL" : value.toString();
    }

    private static String pad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }
}
