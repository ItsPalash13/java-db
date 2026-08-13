package com.example.database.network.requesthandler;

import com.example.database.network.Request;
import com.example.database.network.Response;

/**
 * Bridges the network layer and the query engine.
 * Behavior not implemented yet.
 */
public interface RequestHandler {

    Response handle(Request request);
}
