package com.example.client;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.example.client.wire.ResponsePrinter;
import com.example.client.wire.WireResponse;

/**
 * Interactive REPL or script mode over one TCP session.
 * <p>
 * REPL: {@code [host [port]]}<br>
 * Script: {@code --script in.txt --out out.txt [--stop-on-error] [host [port]]}
 * <p>
 * Request payload stays plain UTF-8 SQL; responses are JSON wire messages rendered
 * by {@link ResponsePrinter}.
 */
public final class Main {

    private static final String PROMPT = "sql> ";

    public static void main(String[] args) throws IOException {
        ClientArgs config;
        try {
            config = ClientArgs.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("usage: [host [port]]");
            System.err.println("   or: --script in.txt --out out.txt [--stop-on-error] [host [port]]");
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        if (config.scriptMode()) {
            runScript(config);
        } else {
            runRepl(config);
        }
    }

    private static void runScript(ClientArgs config) throws IOException {
        var script = config.scriptPath().orElseThrow();
        var out = config.outPath().orElseThrow();
        System.out.println("Connected to " + config.host() + ":" + config.port()
                + " (script " + script + " → " + out + ")");
        int errors;
        try (DatabaseClient client = new DatabaseClient(config.host(), config.port())) {
            ScriptRunner runner = new ScriptRunner(client, config.stopOnError());
            errors = runner.run(script, out, System.out);
        }
        if (errors > 0) {
            System.err.println("Finished with " + errors + " error response(s); transcript: " + out);
            System.exit(1);
        }
        System.out.println("Done; transcript: " + out);
    }

    private static void runRepl(ClientArgs config) throws IOException {
        ResponsePrinter printer = new ResponsePrinter(System.out);
        Console console = System.console();
        try (DatabaseClient client = new DatabaseClient(config.host(), config.port());
             BufferedReader stdin = console == null
                     ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                     : null) {
            System.out.println("Connected to " + config.host() + ":" + config.port() + " (type quit to exit)");
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
