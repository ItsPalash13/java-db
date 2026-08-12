package com.example.client;

import java.io.IOException;

public final class Main {

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 9090;

    public static void main(String[] args) throws IOException {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        String request = args.length > 2 ? args[2] : "PING";

        try (DatabaseClient client = new DatabaseClient(host, port)) {
            String response = client.execute(request);
            System.out.println(response);
        }
    }
}
