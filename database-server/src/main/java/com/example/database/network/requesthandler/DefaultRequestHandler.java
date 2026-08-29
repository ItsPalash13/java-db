package com.example.database.network.requesthandler;



import com.example.database.network.Request;

import com.example.database.network.Response;

import com.example.database.network.tcp.JsonWireResponse;

import com.example.database.processor.QueryProcessor;

import com.example.database.processor.executor.QueryResult;



/**

 * Decode request → {@link QueryProcessor#execute(String)} → JSON {@link WireResponse} frame.

 * Introspection and future SELECT use {@link QueryResult#toWireResponse()} for RESULT_SET.

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

        QueryResult result = queryProcessor.execute(decoded);

        System.out.println("[RequestHandler] got result from QueryProcessor");

        return new JsonWireResponse(result.toWireResponse());

    }

}


