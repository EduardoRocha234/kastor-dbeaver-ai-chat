package io.github.eduardo.dbeaver.aichat.core.llm;

import java.net.http.HttpClient;

public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static LlmProvider create(ProviderConfig config, String apiKey, HttpClient http) {
        switch (config.type()) {
            case ANTHROPIC:
                return new AnthropicProvider(config, apiKey, http);
            case OPENAI_COMPATIBLE:
            default:
                return new OpenAiCompatibleProvider(config, apiKey, http);
        }
    }

    public static HttpClient defaultHttpClient() {
        return HttpStreaming.defaultClient();
    }
}
