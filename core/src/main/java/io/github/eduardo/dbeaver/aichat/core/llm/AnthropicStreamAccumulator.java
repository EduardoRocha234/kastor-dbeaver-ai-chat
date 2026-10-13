package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Rebuilds the assistant content blocks from Messages API stream events. */
final class AnthropicStreamAccumulator {

    private final Consumer<String> onText;
    private final TreeMap<Integer, JsonObject> blocks = new TreeMap<>();
    private final Map<Integer, StringBuilder> partialJson = new HashMap<>();
    private final Map<Integer, String> invalidArguments = new HashMap<>();
    private String stopReason;

    AnthropicStreamAccumulator(Consumer<String> onText) {
        this.onText = onText;
    }

    void accept(String data) throws StreamErrorException {
        JsonObject event;
        try {
            event = JsonParser.parseString(data).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new StreamErrorException("Resposta inválida da Anthropic: " + data);
        }
        String type = Json.str(event, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "content_block_start": {
                int index = event.get("index").getAsInt();
                JsonObject block = Json.obj(event, "content_block");
                block = block == null ? new JsonObject() : block.deepCopy();
                if ("tool_use".equals(Json.str(block, "type"))) {
                    partialJson.put(index, new StringBuilder());
                }
                blocks.put(index, block);
                break;
            }
            case "content_block_delta":
                applyDelta(event.get("index").getAsInt(), Json.obj(event, "delta"));
                break;
            case "content_block_stop":
                finishBlock(event.get("index").getAsInt());
                break;
            case "message_delta": {
                String reason = Json.str(Json.obj(event, "delta"), "stop_reason");
                if (reason != null) {
                    stopReason = reason;
                }
                break;
            }
            case "error":
                throw new StreamErrorException("Erro da Anthropic: " + Json.text(Json.obj(event, "error"), "message"));
            default:
                // message_start, message_stop, ping
        }
    }

    private void applyDelta(int index, JsonObject delta) {
        JsonObject block = blocks.get(index);
        if (block == null || delta == null) {
            return;
        }
        String deltaType = String.valueOf(Json.str(delta, "type"));
        switch (deltaType) {
            case "text_delta": {
                String text = Json.str(delta, "text");
                if (text != null) {
                    append(block, "text", text);
                    onText.accept(text);
                }
                break;
            }
            case "thinking_delta":
                append(block, "thinking", Json.str(delta, "thinking"));
                break;
            case "signature_delta":
                block.addProperty("signature", Json.str(delta, "signature"));
                break;
            case "input_json_delta":
                partialJson.computeIfAbsent(index, i -> new StringBuilder()).append(Json.str(delta, "partial_json"));
                break;
            default:
                // unknown delta types are ignored; the block itself is kept as received
        }
    }

    private static void append(JsonObject block, String field, String value) {
        if (value == null) {
            return;
        }
        String current = Json.str(block, field);
        block.addProperty(field, current == null ? value : current + value);
    }

    private void finishBlock(int index) {
        StringBuilder json = partialJson.remove(index);
        JsonObject block = blocks.get(index);
        if (json == null || block == null) {
            return;
        }
        String raw = json.toString().isBlank() ? "{}" : json.toString();
        try {
            JsonElement input = JsonParser.parseString(raw);
            if (!input.isJsonObject()) {
                throw new IllegalStateException("tool input is not an object");
            }
            block.add("input", input);
        } catch (RuntimeException e) {
            block.add("input", new JsonObject());
            invalidArguments.put(index, raw);
        }
    }

    AssistantTurn finish() {
        int lastFallback = -1;
        for (Map.Entry<Integer, JsonObject> e : blocks.entrySet()) {
            if ("fallback".equals(Json.str(e.getValue(), "type"))) {
                lastFallback = e.getKey();
            }
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JsonArray raw = new JsonArray();
        for (Map.Entry<Integer, JsonObject> e : blocks.entrySet()) {
            JsonObject block = e.getValue();
            raw.add(block);
            String type = String.valueOf(Json.str(block, "type"));
            if ("text".equals(type)) {
                text.append(Json.str(block, "text") == null ? "" : Json.str(block, "text"));
            } else if ("tool_use".equals(type) && e.getKey() > lastFallback) {
                String args = invalidArguments.getOrDefault(e.getKey(),
                        block.has("input") ? block.get("input").toString() : "{}");
                calls.add(new ToolCall(Json.str(block, "id"), Json.str(block, "name"), args));
            }
        }
        StopReason stop;
        switch (stopReason == null ? "" : stopReason) {
            case "tool_use":
                stop = StopReason.TOOL_USE;
                break;
            case "max_tokens":
                stop = StopReason.MAX_TOKENS;
                break;
            case "refusal":
                stop = StopReason.REFUSAL;
                break;
            case "end_turn":
            case "stop_sequence":
                stop = StopReason.END_TURN;
                break;
            default:
                stop = StopReason.OTHER;
        }
        return new AssistantTurn(text.toString(), calls, stop, raw, AnthropicProvider.RAW_FORMAT);
    }
}
