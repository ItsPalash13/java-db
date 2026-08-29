package com.example.client;

import com.example.client.wire.ResponsePrinter;
import com.example.client.wire.WireResponse;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Interactive console: one TCP session, many SQL batches. Request payload stays plain
 * UTF-8 SQL; responses are JSON wire messages rendered by {@link ResponsePrinter}.
 */
public final class Main {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 9090;
    private static final String PROMPT = "sql> ";

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;

        ResponsePrinter printer = new ResponsePrinter(System.out);
        Console console = System.console();
        try (DatabaseClient client = new DatabaseClient(host, port);
             BufferedReader stdin = console == null
                     ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                     : null) {
            System.out.println("Connected to " + host + ":" + port + " (type quit to exit)");
            while (true) {
                String line = readLine(console, stdin);
                if (line == null) {
                    break;
                }
                if (isQuit(line)) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                try {
                    WireResponse response = client.executeQuery(line);
                    printer.print(response);
                } catch (IOException e) {
                    System.err.println("Connection error: " + e.getMessage());
                    break;
                }
            }
        }
    }

    /**
     * {@link Console#readLine(String)} keeps prompt and input on one line when a real console
     * exists. {@code mvn exec:java} often has {@code console == null} and captures stdout, so
     * we print the prompt on stderr there — Maven still forwards stderr to the terminal.
     */
    private static String readLine(Console console, BufferedReader stdin) throws IOException {
        if (console != null) {
            return console.readLine(PROMPT);
        }
        System.err.print(PROMPT);
        System.err.flush();
        return stdin.readLine();
    }

    private static boolean isQuit(String line) {
        String trimmed = line.trim();
        return "quit".equalsIgnoreCase(trimmed) || "exit".equalsIgnoreCase(trimmed);
    }
}
