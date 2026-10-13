package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Anthropic Messages API over raw HTTP + SSE. */
public final class AnthropicProvider implements LlmProvider {

    public static final String RAW_FORMAT = "anthropic";
    public static final int DEFAULT_MAX_TOKENS = 64000;
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private static final String API_VERSION = "2023-06-01";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final ProviderConfig config;
    private final String apiKey;
    private final HttpClient http;

    public AnthropicProvider(ProviderConfig config, String apiKey, HttpClient http) {
        this.config = config;
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException {
        try {
            return doStream(request, onTextDelta, cancel, config.serverFallback());
        } catch (LlmException e) {
            boolean fallbackRejected = e.status() == 400
                    && e.getMessage().toLowerCase(Locale.ROOT).contains("fallback");
            if (config.serverFallback() && fallbackRejected) {
                return doStream(request, onTextDelta, cancel, false);
            }
            throw e;
        }
    }

    private AssistantTurn doStream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel,
            boolean serverFallback) throws LlmException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-api-key", apiKey.trim());
        }
        headers.put("anthropic-version", API_VERSION);
        if (serverFallback) {
            headers.put("anthropic-beta", FALLBACK_BETA);
        }
        String baseUrl = config.baseUrl() == null || config.baseUrl().isBlank() ? DEFAULT_BASE_URL : config.baseUrl();
        String body = AnthropicMapper.requestBody(request, serverFallback).toString();
        InputStream in = HttpStreaming.post(http, HttpStreaming.endpoint(baseUrl, "/v1/messages"), headers, body, cancel);
        AnthropicStreamAccumulator accumulator = new AnthropicStreamAccumulator(onTextDelta);
        try (in; CancellationToken.Registration registration = cancel.onCancel(() -> HttpStreaming.closeQuietly(in))) {
            SseParser.parse(in, (event, data) -> accumulator.accept(data), cancel);
        } catch (StreamErrorException e) {
            throw new LlmException(e.getMessage(), 0);
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new LlmException("Conexão interrompida: " + e.getMessage(), 0);
        }
        return accumulator.finish();
    }
}
