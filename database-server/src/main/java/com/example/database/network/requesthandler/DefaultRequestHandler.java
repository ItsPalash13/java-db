package com.example.database.network.requesthandler;

import com.example.database.network.Request;
import com.example.database.network.Response;
import com.example.database.network.tcp.JsonWireResponse;
import com.example.database.network.wire.WireResponseEncoder;
import com.example.database.processor.QueryProcessor;

/**
 * Decode request → {@link QueryProcessor#execute(String)} → JSON {@link WireResponse} frame.
 * <p>
 * Processor output remains plain text for unit tests; only the network edge wraps it in typed
 * wire messages so the client can render OK / ERROR / RESULT_SET without parsing ad hoc strings.
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
        return new JsonWireResponse(WireResponseEncoder.fromProcessorText(result));
    }
}
