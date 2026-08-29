package com.example.database.network.wire;

import java.util.List;

/**
 * Maps {@link com.example.database.processor.QueryProcessor} text outcomes to structured wire
 * messages. Encoding stays at the network edge so lex/parse/executor tests keep using plain
 * {@code "OK"} / {@code "ERROR: …"} strings.
 */
public final class WireResponseEncoder {

    private WireResponseEncoder() {
    }

    /**
     * @param processorText return value of {@code QueryProcessor.execute()}
     */
    public static WireResponse fromProcessorText(String processorText) {
        if (processorText.startsWith("ERROR")) {
            return new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Error(processorText)));
        }
        if ("OK".equals(processorText)) {
            return new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Ok(0)));
        }
        if (processorText.startsWith("OK ")) {
            // Unresolved plans still echo "OK <query>" until SELECT returns RESULT_SET.
            return new WireResponse(WireProtocol.VERSION, List.of(new WireMessage.Ok(0)));
        }
        return new WireResponse(
                WireProtocol.VERSION,
                List.of(new WireMessage.Error("unexpected processor response: " + processorText))
        );
    }
}
