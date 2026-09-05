package com.example.client;

import com.example.client.wire.ResponsePrinter;
import com.example.client.wire.WireMessage;
import com.example.client.wire.WireResponse;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Batch mode: run each non-comment line of a script file as one SQL request,
 * wait for the wire reply, and append a transcript to an output file.
 * <p>
 * One TCP session for the whole file — same synchronous contract as the REPL.
 */
public final class ScriptRunner {

    private final DatabaseClient client;
    private final boolean stopOnError;

    public ScriptRunner(DatabaseClient client, boolean stopOnError) {
        this.client = Objects.requireNonNull(client, "client");
        this.stopOnError = stopOnError;
    }

    /**
     * Execute {@code scriptPath} and write the transcript to {@code outPath}.
     * Also echoes the same text to {@code echo} when non-null (usually stdout).
     *
     * @return number of statements that returned a wire {@code ERROR} message
     */
    public int run(Path scriptPath, Path outPath, PrintStream echo) throws IOException {
        Objects.requireNonNull(scriptPath, "scriptPath");
        Objects.requireNonNull(outPath, "outPath");
        List<String> lines = Files.readAllLines(scriptPath, StandardCharsets.UTF_8);
        Path parent = outPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int errors = 0;
        try (PrintStream out = new PrintStream(Files.newOutputStream(outPath), true, StandardCharsets.UTF_8)) {
            ResponsePrinter filePrinter = new ResponsePrinter(out);
            ResponsePrinter echoPrinter = echo == null ? null : new ResponsePrinter(echo);
            for (String raw : lines) {
                String sql = normalizeStatement(raw);
                if (sql == null) {
                    continue;
                }
                writeBoth(out, echo, ">>> " + sql);
                try {
                    WireResponse response = client.executeQuery(sql);
                    filePrinter.print(response);
                    if (echoPrinter != null) {
                        echoPrinter.print(response);
                    }
                    writeBoth(out, echo, "");
                    if (containsError(response)) {
                        errors++;
                        if (stopOnError) {
                            writeBoth(out, echo, "# stopped on error");
                            break;
                        }
                    }
                } catch (IOException e) {
                    writeBoth(out, echo, "Connection error: " + e.getMessage());
                    throw e;
                }
            }
        }
        return errors;
    }

    /**
     * Blank and {@code #}/{@code --} comment lines are skipped; otherwise the trimmed line
     * is one SQL batch (no trailing {@code ;} — same as the interactive client).
     */
    static String normalizeStatement(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("#") || trimmed.startsWith("--")) {
            return null;
        }
        return trimmed;
    }

    private static boolean containsError(WireResponse response) {
        for (WireMessage message : response.messages()) {
            if (message instanceof WireMessage.Error) {
                return true;
            }
        }
        return false;
    }

    private static void writeBoth(PrintStream out, PrintStream echo, String line) {
        out.println(line);
        if (echo != null) {
            echo.println(line);
        }
    }
}
