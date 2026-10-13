package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import java.util.List;

/**
 * One complete model response. rawContent/rawFormat keep the provider-native content
 * (e.g. Anthropic thinking blocks with signatures) so it can be echoed back unchanged.
 */
public record AssistantTurn(String text, List<ToolCall> toolCalls, StopReason stopReason,
        JsonArray rawContent, String rawFormat) {

    public AssistantTurn {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        stopReason = stopReason == null ? StopReason.OTHER : stopReason;
    }

    public boolean isEmpty() {
        return text.isEmpty() && toolCalls.isEmpty() && (rawContent == null || rawContent.isEmpty());
    }
}
