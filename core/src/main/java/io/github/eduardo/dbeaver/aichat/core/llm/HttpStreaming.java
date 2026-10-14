package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** POST + streaming response body shared by all providers. */
public final class HttpStreaming {

    private HttpStreaming() {
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    public static URI endpoint(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    public static InputStream post(HttpClient http, URI uri, Map<String, String> headers, String body,
            CancellationToken cancel) throws LlmException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try (CancellationToken.Registration registration = cancel.onCancel(() -> future.cancel(true))) {
            response = future.get();
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new LlmException("Falha ao conectar em " + uri + ": " + cause, 0);
        }
        if (response.statusCode() / 100 != 2) {
            String error;
            try (InputStream in = response.body()) {
                error = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException e) {
                error = "";
            }
            throw new LlmException("HTTP " + response.statusCode() + " de " + uri + ": " + error, response.statusCode());
        }
        return response.body();
    }

    /** GET returning the whole body as text; throws on non-2xx. */
    public static String get(HttpClient http, URI uri, Map<String, String> headers) throws LlmException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrompido ao conectar em " + uri, 0);
        } catch (IOException e) {
            throw new LlmException("Falha ao conectar em " + uri + ": " + e, 0);
        }
        if (response.statusCode() / 100 != 2) {
            String body = response.body();
            throw new LlmException("HTTP " + response.statusCode() + " de " + uri + ": "
                    + (body.length() > 500 ? body.substring(0, 500) : body), response.statusCode());
        }
        return response.body();
    }

    public static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing to abort a stream
        }
    }
}
