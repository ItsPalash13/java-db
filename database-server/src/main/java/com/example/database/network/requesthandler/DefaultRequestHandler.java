package com.example.database.network.requesthandler;

import com.example.database.engine.QueryEngine;
import com.example.database.network.Request;
import com.example.database.network.Response;
import com.example.database.network.tcp.TextResponse;

/**
 * Bridges the network layer and the query engine.
 */
public final class DefaultRequestHandler implements RequestHandler {

    private final QueryEngine queryEngine;

    public DefaultRequestHandler(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    @Override
    public Response handle(Request request) {
        System.out.println("[RequestHandler] checked request");
        String decoded = request.decode();
        System.out.println("[RequestHandler] decoded payload: " + decoded);
        System.out.println("[RequestHandler] forwarding to QueryEngine");
        String result = queryEngine.execute(decoded);
        System.out.println("[RequestHandler] got result from QueryEngine");
        return new TextResponse(result);
    }
}
