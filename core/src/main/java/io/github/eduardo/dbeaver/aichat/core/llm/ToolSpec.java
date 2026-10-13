package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonObject;

public record ToolSpec(String name, String description, JsonObject inputSchema) {
}
