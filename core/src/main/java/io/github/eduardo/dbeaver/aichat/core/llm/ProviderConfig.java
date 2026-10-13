package io.github.eduardo.dbeaver.aichat.core.llm;

/** User-configured provider. The API key is stored separately (secure storage). */
public record ProviderConfig(String id, String name, ProviderType type, String baseUrl, String model,
        int maxTokens, Double temperature, boolean serverFallback) {
}
