package com.example.database.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default engine stub: echoes {@code OK <query>}.
 * Swap at the composition root ({@code Main}) with any other {@link QueryEngine} implementation.
 */
public final class DefaultQueryEngine implements QueryEngine {

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        System.out.println("[QueryEngine] started");
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        System.out.println("[QueryEngine] stopped");
    }

    @Override
    public String execute(String query) {
        if (!running.get()) {
            throw new IllegalStateException("QueryEngine is not started");
        }
        System.out.println("[QueryEngine] executing query: " + query);
        String result = "OK " + query;
        System.out.println("[QueryEngine] result: " + result);
        return result;
    }
}
