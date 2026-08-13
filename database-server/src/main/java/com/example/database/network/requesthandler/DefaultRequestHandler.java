package com.example.database.network.requesthandler;

import com.example.database.engine.QueryEngine;
import com.example.database.network.Request;
import com.example.database.network.Response;

/**
 * Default request handler stub. Behavior not implemented yet.
 */
public final class DefaultRequestHandler implements RequestHandler {

    private final QueryEngine queryEngine;

    public DefaultRequestHandler(QueryEngine queryEngine) {
        this.queryEngine = queryEngine;
    }

    @Override
    public Response handle(Request request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
