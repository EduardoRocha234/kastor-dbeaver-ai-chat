package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Builds Chat Completions request bodies. Ignores provider-native raw content from other formats. */
final class OpenAiMapper {

    private OpenAiMapper() {
    }

    static JsonObject requestBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        JsonArray messages = new JsonArray();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(message("system", request.systemPrompt()));
        }
        for (ChatMessage m : request.messages()) {
            messages.add(toJson(m));
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", spec.name());
                function.addProperty("description", spec.description());
                function.add("parameters", spec.inputSchema());
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        if (request.maxTokens() > 0) {
            body.addProperty("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.addProperty("temperature", request.temperature());
        }
        return body;
    }

    private static JsonObject toJson(ChatMessage m) {
        switch (m.role()) {
            case USER:
                return message("user", m.text());
            case TOOL: {
                JsonObject o = message("tool", m.text());
                o.addProperty("tool_call_id", m.toolCallId());
                return o;
            }
            default: {
                JsonObject o = new JsonObject();
                o.addProperty("role", "assistant");
                if (m.text().isEmpty()) {
                    o.add("content", JsonNull.INSTANCE);
                } else {
                    o.addProperty("content", m.text());
                }
                if (!m.toolCalls().isEmpty()) {
                    JsonArray calls = new JsonArray();
                    for (ToolCall call : m.toolCalls()) {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name());
                        function.addProperty("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                        JsonObject c = new JsonObject();
                        c.addProperty("id", call.id());
                        c.addProperty("type", "function");
                        c.add("function", function);
                        calls.add(c);
                    }
                    o.add("tool_calls", calls);
                }
                return o;
            }
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
