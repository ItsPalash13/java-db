package com.example.database;

import com.example.database.storage.DataDirectory;

import java.nio.file.Path;

/**
 * Process-edge launch options: listen port and store root.
 * <p>
 * Args: {@code [port] [dataDir]} or {@code --port N} / {@code --data-dir PATH}.
 * Missing data dir uses {@link DataDirectory#defaults()} ({@code ./data}).
 */
public final class LaunchConfig {

    public static final int DEFAULT_PORT = 9090;

    private final int port;
    private final Path dataDir;

    public LaunchConfig(int port, Path dataDir) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        this.port = port;
        this.dataDir = dataDir;
    }

    public static LaunchConfig parse(String[] args) {
        int port = DEFAULT_PORT;
        Path dataDir = Path.of(DataDirectory.DEFAULT_FOLDER_NAME);
        boolean portSet = false;
        boolean dataDirSet = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equals(arg)) {
                port = Integer.parseInt(requireValue(args, ++i, "--port"));
                portSet = true;
            } else if ("--data-dir".equals(arg) || "--datadir".equals(arg)) {
                dataDir = Path.of(requireValue(args, ++i, "--data-dir"));
                dataDirSet = true;
            } else if (arg.startsWith("-")) {
                throw new IllegalArgumentException("unknown option: " + arg + usage());
            } else if (isPortToken(arg) && !portSet) {
                port = Integer.parseInt(arg);
                portSet = true;
            } else if (!dataDirSet) {
                dataDir = Path.of(arg);
                dataDirSet = true;
            } else {
                throw new IllegalArgumentException("unexpected argument: " + arg + usage());
            }
        }
        return new LaunchConfig(port, dataDir);
    }

    public int port() {
        return port;
    }

    public Path dataDir() {
        return dataDir;
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + option + usage());
        }
        return args[index];
    }

    private static boolean isPortToken(String arg) {
        try {
            int value = Integer.parseInt(arg);
            return value >= 0 && value <= 65535;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String usage() {
        return " (usage: [port] [dataDir] | --port N --data-dir PATH)";
    }
}
