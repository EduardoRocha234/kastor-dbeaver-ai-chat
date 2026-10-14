package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Asks the provider which models it serves: GET {baseUrl}/models (OpenAI-compatible) or /v1/models (Anthropic). */
public final class ModelLister {

    private ModelLister() {
    }

    public static List<String> list(ProviderConfig config, String apiKey, HttpClient http) throws LlmException {
        Map<String, String> headers = new LinkedHashMap<>();
        String url;
        if (config.type() == ProviderType.ANTHROPIC) {
            if (apiKey != null && !apiKey.isBlank()) {
                headers.put("x-api-key", apiKey.trim());
            }
            headers.put("anthropic-version", "2023-06-01");
            String base = config.baseUrl() == null || config.baseUrl().isBlank()
                    ? AnthropicProvider.DEFAULT_BASE_URL : config.baseUrl();
            url = HttpStreaming.endpoint(base, "/v1/models?limit=1000").toString();
        } else {
            if (apiKey != null && !apiKey.isBlank()) {
                headers.put("Authorization", "Bearer " + apiKey.trim());
            }
            url = HttpStreaming.endpoint(config.baseUrl(), "/models").toString();
        }
        String body = HttpStreaming.get(http, java.net.URI.create(url), headers);
        try {
            return parse(body);
        } catch (RuntimeException e) {
            throw new LlmException("Resposta inválida de " + url, 0);
        }
    }

    /** Model ids from {"data":[{"id":...}]}, sorted, without duplicates. */
    static List<String> parse(String body) {
        JsonElement root = JsonParser.parseString(body);
        JsonArray data = root.isJsonObject() ? Json.arr(root.getAsJsonObject(), "data") : null;
        List<String> ids = new ArrayList<>();
        if (data != null) {
            for (JsonElement e : data) {
                String id = e.isJsonObject() ? Json.str((JsonObject) e, "id") : null;
                if (id != null && !id.isBlank() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        }
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        return ids;
    }
}
