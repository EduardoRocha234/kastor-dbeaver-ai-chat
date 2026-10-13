package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;
import java.util.Set;

/** Builds Messages API request bodies. */
final class AnthropicMapper {

    private static final Set<String> DROPPED_BEFORE_FALLBACK = Set.of("thinking", "redacted_thinking", "tool_use");

    private AnthropicMapper() {
    }

    static JsonObject requestBody(ChatRequest request, boolean serverFallback) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : AnthropicProvider.DEFAULT_MAX_TOKENS);
        body.addProperty("stream", true);
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.addProperty("system", request.systemPrompt());
        }
        body.add("messages", messages(request.messages()));
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", spec.name());
                tool.addProperty("description", spec.description());
                tool.add("input_schema", spec.inputSchema());
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        if (serverFallback) {
            body.addProperty("fallbacks", "default");
        }
        return body;
    }

    private static JsonArray messages(List<ChatMessage> history) {
        JsonArray out = new JsonArray();
        for (ChatMessage m : history) {
            switch (m.role()) {
                case TOOL: {
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "tool_result");
                    result.addProperty("tool_use_id", m.toolCallId());
                    result.addProperty("content", m.text());
                    if (m.error()) {
                        result.addProperty("is_error", true);
                    }
                    appendUser(out, result);
                    break;
                }
                case USER: {
                    appendUser(out, textBlock(m.text()));
                    break;
                }
                default: {
                    JsonArray content = assistantContent(m);
                    if (!content.isEmpty()) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("role", "assistant");
                        msg.add("content", content);
                        out.add(msg);
                    }
                }
            }
        }
        return out;
    }

    /** Consecutive user-side blocks (tool results, text) go into a single user message. */
    private static void appendUser(JsonArray out, JsonObject block) {
        if (!out.isEmpty()) {
            JsonObject last = out.get(out.size() - 1).getAsJsonObject();
            if ("user".equals(Json.str(last, "role"))) {
                last.getAsJsonArray("content").add(block);
                return;
            }
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        content.add(block);
        msg.add("content", content);
        out.add(msg);
    }

    private static JsonArray assistantContent(ChatMessage m) {
        if (AnthropicProvider.RAW_FORMAT.equals(m.rawFormat()) && m.rawContent() != null) {
            return echoContent(m.rawContent());
        }
        JsonArray content = new JsonArray();
        if (!m.text().isEmpty()) {
            content.add(textBlock(m.text()));
        }
        for (ToolCall call : m.toolCalls()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_use");
            block.addProperty("id", call.id());
            block.addProperty("name", call.name());
            block.add("input", parseInputOrEmpty(call.argumentsJson()));
            content.add(block);
        }
        return content;
    }

    /**
     * Content blocks to send back unchanged, except that thinking/redacted_thinking/tool_use blocks
     * before the last server-side "fallback" block are omitted, as the API requires.
     */
    static JsonArray echoContent(JsonArray raw) {
        int lastFallback = -1;
        for (int i = 0; i < raw.size(); i++) {
            if ("fallback".equals(type(raw.get(i)))) {
                lastFallback = i;
            }
        }
        JsonArray out = new JsonArray();
        for (int i = 0; i < raw.size(); i++) {
            if (i < lastFallback && DROPPED_BEFORE_FALLBACK.contains(type(raw.get(i)))) {
                continue;
            }
            out.add(raw.get(i).deepCopy());
        }
        return out;
    }

    private static String type(JsonElement block) {
        return block.isJsonObject() ? Json.str(block.getAsJsonObject(), "type") : null;
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static JsonObject parseInputOrEmpty(String json) {
        try {
            JsonElement e = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
