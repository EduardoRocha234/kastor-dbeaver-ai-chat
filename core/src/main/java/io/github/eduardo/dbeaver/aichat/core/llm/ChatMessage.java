package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import java.util.List;

public record ChatMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId,
        boolean error, JsonArray rawContent, String rawFormat) {

    public ChatMessage {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), null, false, null, null);
    }

    public static ChatMessage assistant(AssistantTurn turn) {
        return new ChatMessage(Role.ASSISTANT, turn.text(), turn.toolCalls(), null, false,
                turn.rawContent(), turn.rawFormat());
    }

    public static ChatMessage toolResult(String toolCallId, String content, boolean error) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, error, null, null);
    }
}
