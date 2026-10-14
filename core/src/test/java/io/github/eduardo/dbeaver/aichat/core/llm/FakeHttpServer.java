package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local HTTP server that replays queued responses and records requests. */
public final class FakeHttpServer implements AutoCloseable {

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Queue<Response> queue = new ConcurrentLinkedQueue<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile String lastPath;
    volatile Headers lastHeaders;

    public FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastHeaders = exchange.getRequestHeaders();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Response r = queue.poll();
            if (r == null) {
                r = new Response(500, "{\"error\":{\"message\":\"no response queued\"}}");
            }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", r.status() == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(r.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    public void enqueue(int status, String body) {
        queue.add(new Response(status, body));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    JsonObject lastJson() {
        return JsonParser.parseString(bodies.get(bodies.size() - 1)).getAsJsonObject();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
