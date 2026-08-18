package com.example.database.network.requesthandler;

import com.example.database.network.Request;
import com.example.database.network.Response;
import com.example.database.network.tcp.TextResponse;
import com.example.database.processor.QueryProcessor;

/**
 * Default bridge: decode request → {@link QueryProcessor#execute(String)} → {@link TextResponse}.
 */
public final class DefaultRequestHandler implements RequestHandler {

    private final QueryProcessor queryProcessor;

    public DefaultRequestHandler(QueryProcessor queryProcessor) {
        this.queryProcessor = queryProcessor;
    }

    @Override
    public Response handle(Request request) {
        System.out.println("[RequestHandler] checked request");
        String decoded = request.decode();
        System.out.println("[RequestHandler] decoded payload: " + decoded);
        System.out.println("[RequestHandler] forwarding to QueryProcessor");
        String result = queryProcessor.execute(decoded);
        System.out.println("[RequestHandler] got result from QueryProcessor");
        return new TextResponse(result);
    }
}
