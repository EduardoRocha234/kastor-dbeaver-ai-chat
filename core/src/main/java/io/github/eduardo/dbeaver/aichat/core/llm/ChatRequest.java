package io.github.eduardo.dbeaver.aichat.core.llm;

import java.util.List;

/** maxTokens <= 0 means provider default; temperature null means provider default. */
public record ChatRequest(String model, String systemPrompt, List<ChatMessage> messages,
        List<ToolSpec> tools, int maxTokens, Double temperature) {

    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
