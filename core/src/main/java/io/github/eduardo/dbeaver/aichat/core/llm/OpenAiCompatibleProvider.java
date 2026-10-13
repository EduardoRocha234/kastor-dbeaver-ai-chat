package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Any server implementing POST {baseUrl}/chat/completions with SSE (OpenAI, Ollama, LM Studio, OpenRouter...). */
public final class OpenAiCompatibleProvider implements LlmProvider {

    private final ProviderConfig config;
    private final String apiKey;
    private final HttpClient http;

    public OpenAiCompatibleProvider(ProviderConfig config, String apiKey, HttpClient http) {
        this.config = config;
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey.trim());
        }
        String body = OpenAiMapper.requestBody(request).toString();
        InputStream in = HttpStreaming.post(http, HttpStreaming.endpoint(config.baseUrl(), "/chat/completions"),
                headers, body, cancel);
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(onTextDelta);
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
