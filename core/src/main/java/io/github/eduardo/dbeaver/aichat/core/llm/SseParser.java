package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Minimal text/event-stream parser (event + data fields). */
public final class SseParser {

    public interface Handler {
        void onEvent(String event, String data) throws IOException;
    }

    private SseParser() {
    }

    public static void parse(InputStream in, Handler handler, CancellationToken cancel) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            cancel.throwIfCancelled();
            if (line.isEmpty()) {
                if (hasData) {
                    handler.onEvent(event, data.toString());
                }
                event = null;
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                event = value;
            } else if ("data".equals(field)) {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
        }
        cancel.throwIfCancelled();
        if (hasData) {
            handler.onEvent(event, data.toString());
        }
    }
}
