package io.github.eduardo.dbeaver.aichat.core.llm;

public record ToolCall(String id, String name, String argumentsJson) {
}
