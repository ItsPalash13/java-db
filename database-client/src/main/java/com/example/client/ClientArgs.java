package com.example.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed {@link Main} argv: REPL (host/port only) or script mode
 * ({@code --script} / {@code --out}).
 */
public final class ClientArgs {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 9090;

    private final String host;
    private final int port;
    private final Path scriptPath;
    private final Path outPath;
    private final boolean stopOnError;

    private ClientArgs(String host, int port, Path scriptPath, Path outPath, boolean stopOnError) {
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.scriptPath = scriptPath;
        this.outPath = outPath;
        this.stopOnError = stopOnError;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public Optional<Path> scriptPath() {
        return Optional.ofNullable(scriptPath);
    }

    public Optional<Path> outPath() {
        return Optional.ofNullable(outPath);
    }

    public boolean stopOnError() {
        return stopOnError;
    }

    public boolean scriptMode() {
        return scriptPath != null;
    }

    /**
     * Accepts {@code [host [port]]} for REPL, or
     * {@code --script path --out path [--stop-on-error] [host [port]]}.
     */
    public static ClientArgs parse(String[] args) {
        Objects.requireNonNull(args, "args");
        Path script = null;
        Path out = null;
        boolean stopOnError = false;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--script".equals(arg)) {
                script = Path.of(requireValue(args, ++i, "--script"));
            } else if ("--out".equals(arg)) {
                out = Path.of(requireValue(args, ++i, "--out"));
            } else if ("--stop-on-error".equals(arg)) {
                stopOnError = true;
            } else if (arg.startsWith("-")) {
                throw new IllegalArgumentException("unknown option: " + arg);
            } else {
                positional.add(arg);
            }
        }
        if (script != null && out == null) {
            throw new IllegalArgumentException("--script requires --out");
        }
        if (script == null && out != null) {
            throw new IllegalArgumentException("--out requires --script");
        }
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (positional.size() >= 1) {
            host = positional.get(0);
        }
        if (positional.size() >= 2) {
            try {
                port = Integer.parseInt(positional.get(1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid port: " + positional.get(1), e);
            }
        }
        if (positional.size() > 2) {
            throw new IllegalArgumentException("unexpected arguments: " + positional.subList(2, positional.size()));
        }
        return new ClientArgs(host, port, script, out, stopOnError);
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " requires a path argument");
        }
        return args[index];
    }
}
