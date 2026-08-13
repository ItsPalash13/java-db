package com.example.database.network.requesthandler;

import com.example.database.network.Request;
import com.example.database.network.Response;

/**
 * Bridge between the network layer and {@link com.example.database.engine.QueryEngine}.
 * Owned by {@link com.example.database.network.tcp.TcpNetworkModule}.
 */
public interface RequestHandler {

    Response handle(Request request);
}
