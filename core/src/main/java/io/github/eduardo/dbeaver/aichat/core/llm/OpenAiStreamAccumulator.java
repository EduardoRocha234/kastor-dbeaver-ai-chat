package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Folds Chat Completions stream chunks into one AssistantTurn. */
final class OpenAiStreamAccumulator {

    private static final class PartialCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private final Consumer<String> onText;
    private final StringBuilder text = new StringBuilder();
    private final TreeMap<Integer, PartialCall> calls = new TreeMap<>();
    private String finishReason;

    OpenAiStreamAccumulator(Consumer<String> onText) {
        this.onText = onText;
    }

    void accept(String data) throws StreamErrorException {
        if ("[DONE]".equals(data.trim())) {
            return;
        }
        JsonObject chunk;
        try {
            chunk = JsonParser.parseString(data).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new StreamErrorException("Resposta inválida do provedor: " + data);
        }
        JsonObject error = Json.obj(chunk, "error");
        if (error != null) {
            throw new StreamErrorException("Erro do provedor: " + Json.text(error, "message"));
        }
        JsonArray choices = Json.arr(chunk, "choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = Json.obj(choice, "delta");
        if (delta != null) {
            String content = Json.str(delta, "content");
            if (content != null && !content.isEmpty()) {
                text.append(content);
                onText.accept(content);
            }
            JsonArray toolCalls = Json.arr(delta, "tool_calls");
            if (toolCalls != null) {
                for (JsonElement element : toolCalls) {
                    JsonObject tc = element.getAsJsonObject();
                    int index = tc.has("index") ? tc.get("index").getAsInt() : calls.size();
                    PartialCall partial = calls.computeIfAbsent(index, i -> new PartialCall());
                    String id = Json.str(tc, "id");
                    if (id != null) {
                        partial.id = id;
                    }
                    JsonObject function = Json.obj(tc, "function");
                    if (function != null) {
                        String name = Json.str(function, "name");
                        if (name != null) {
                            partial.name = name;
                        }
                        String args = Json.text(function, "arguments");
                        if (args != null) {
                            partial.arguments.append(args);
                        }
                    }
                }
            }
        }
        String reason = Json.str(choice, "finish_reason");
        if (reason != null) {
            finishReason = reason;
        }
    }

    AssistantTurn finish() {
        List<ToolCall> toolCalls = new ArrayList<>();
        calls.forEach((index, p) -> toolCalls.add(
                new ToolCall(p.id != null ? p.id : "call_" + index, p.name, p.arguments.toString())));
        StopReason stop;
        switch (finishReason == null ? "" : finishReason) {
            case "length":
                stop = StopReason.MAX_TOKENS;
                break;
            case "content_filter":
                stop = StopReason.REFUSAL;
                break;
            case "tool_calls":
            case "function_call":
                stop = StopReason.TOOL_USE;
                break;
            default:
                stop = StopReason.END_TURN;
        }
        if (stop == StopReason.END_TURN && !toolCalls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        }
        return new AssistantTurn(text.toString(), toolCalls, stop, null, null);
    }
}
