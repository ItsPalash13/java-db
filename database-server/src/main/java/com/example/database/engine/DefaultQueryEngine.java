package com.example.database.engine;

/**
 * Default query engine stub — echoes the query for now.
 */
public final class DefaultQueryEngine implements QueryEngine {

    @Override
    public String execute(String query) {
        System.out.println("[QueryEngine] executing query: " + query);
        String result = "OK " + query;
        System.out.println("[QueryEngine] result: " + result);
        return result;
    }
}
