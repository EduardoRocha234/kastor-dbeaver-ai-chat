package io.github.eduardo.dbeaver.aichat.settings;

import io.github.eduardo.dbeaver.aichat.core.llm.AnthropicProvider;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import java.util.List;

/** Starting values offered when adding a provider. Empty model = the user must type one. */
final class ProviderPresets {

    record Preset(String label, ProviderType type, String baseUrl, String model, boolean serverFallback) {
    }

    static final List<Preset> ALL = List.of(
            new Preset("Anthropic (Claude)", ProviderType.ANTHROPIC, AnthropicProvider.DEFAULT_BASE_URL, "claude-opus-5-5", true),
            new Preset("OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "", false),
            new Preset("Ollama (local)", ProviderType.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "", false),
            new Preset("LM Studio (local)", ProviderType.OPENAI_COMPATIBLE, "http://localhost:1234/v1", "", false),
            new Preset("OpenRouter", ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", false),
            new Preset("Personalizado (OpenAI-compatible)", ProviderType.OPENAI_COMPATIBLE, "", "", false));

    private ProviderPresets() {
    }
}
