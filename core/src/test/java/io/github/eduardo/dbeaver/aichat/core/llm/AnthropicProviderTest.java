package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicProviderTest {

    private static final String TEXT_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Olá"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static ProviderConfig config(String baseUrl, boolean fallback) {
        return new ProviderConfig("a1", "Claude", ProviderType.ANTHROPIC, baseUrl, "claude-opus-5-5", 0, 0.7, fallback);
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest("claude-opus-5-5", "sys", List.of(ChatMessage.user("Oi")), List.of(), 0, 0.7);
    }

    @Test
    void streamsTextWithAnthropicHeaders() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM);
            List<String> deltas = new ArrayList<>();
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), false), "sk-ant", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), deltas::add, new CancellationToken());

            assertEquals(List.of("Olá"), deltas);
            assertEquals("Olá", turn.text());
            assertEquals(StopReason.END_TURN, turn.stopReason());
            assertEquals("/v1/messages", server.lastPath);
            assertEquals("sk-ant", server.lastHeaders.getFirst("x-api-key"));
            assertEquals("2023-06-01", server.lastHeaders.getFirst("anthropic-version"));
            assertNull(server.lastHeaders.getFirst("anthropic-beta"));
            assertFalse(server.lastJson().has("fallbacks"));
        }
    }

    @Test
    void keepsThinkingBlocksAndParsesToolInput() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Vou consultar."}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":1}

                    event: content_block_start
                    data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"run_query","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"sql\\":"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\\"select 1\\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":2}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """);
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());

            assertEquals(StopReason.TOOL_USE, turn.stopReason());
            assertEquals("Vou consultar.", turn.text());
            assertEquals(AnthropicProvider.RAW_FORMAT, turn.rawFormat());
            ToolCall call = turn.toolCalls().get(0);
            assertEquals("toolu_1", call.id());
            assertEquals("run_query", call.name());
            assertEquals("select 1", JsonParser.parseString(call.argumentsJson()).getAsJsonObject().get("sql").getAsString());
            JsonObject thinking = turn.rawContent().get(0).getAsJsonObject();
            assertEquals("thinking", thinking.get("type").getAsString());
            assertEquals("sig123", thinking.get("signature").getAsString());
            assertEquals("select 1", turn.rawContent().get(2).getAsJsonObject()
                    .getAsJsonObject("input").get("sql").getAsString());
        }
    }

    @Test
    void mapsHistoryToMessagesApiFormat() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM);
            JsonArray raw = new JsonArray();
            raw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s1\"}"));
            raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"list_tables\",\"input\":{}}"));
            raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"list_schemas\",\"input\":{}}"));
            List<ChatMessage> messages = List.of(
                    ChatMessage.user("Quais tabelas?"),
                    new ChatMessage(Role.ASSISTANT, "", List.of(new ToolCall("toolu_1", "list_tables", "{}"),
                            new ToolCall("toolu_2", "list_schemas", "{}")), null, false, raw, AnthropicProvider.RAW_FORMAT),
                    ChatMessage.toolResult("toolu_1", "- orders", false),
                    ChatMessage.toolResult("toolu_2", "falhou", true));
            ToolSpec spec = new ToolSpec("list_tables", "Lista tabelas", Json.parseObject("{\"type\":\"object\",\"properties\":{}}"));
            ChatRequest request = new ChatRequest("claude-opus-5-5", "sys", messages, List.of(spec), 0, 0.7);

            new AnthropicProvider(config(server.baseUrl(), true), "k", HttpStreaming.defaultClient())
                    .stream(request, t -> { }, new CancellationToken());

            JsonObject body = server.lastJson();
            assertEquals("sys", body.get("system").getAsString());
            assertEquals(AnthropicProvider.DEFAULT_MAX_TOKENS, body.get("max_tokens").getAsInt());
            assertFalse(body.has("temperature"));
            assertEquals("default", body.get("fallbacks").getAsString());
            assertEquals("server-side-fallback-2026-07-01", server.lastHeaders.getFirst("anthropic-beta"));
            JsonArray sent = body.getAsJsonArray("messages");
            assertEquals(3, sent.size());
            assertEquals(raw, sent.get(1).getAsJsonObject().getAsJsonArray("content"));
            JsonArray results = sent.get(2).getAsJsonObject().getAsJsonArray("content");
            assertEquals("user", sent.get(2).getAsJsonObject().get("role").getAsString());
            assertEquals(2, results.size());
            assertEquals("tool_result", results.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("toolu_2", results.get(1).getAsJsonObject().get("tool_use_id").getAsString());
            assertTrue(results.get(1).getAsJsonObject().get("is_error").getAsBoolean());
            assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
        }
    }

    @Test
    void buildsContentFromTextAndToolCallsWhenNoRawContent() throws Exception {
        // History produced by an OpenAI-compatible provider, then sent to Anthropic.
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM);
            List<ChatMessage> messages = List.of(
                    ChatMessage.user("Oi"),
                    ChatMessage.assistant(new AssistantTurn("Vou ver", List.of(new ToolCall("call_1", "list_connections", "{}")),
                            StopReason.TOOL_USE, null, null)),
                    ChatMessage.toolResult("call_1", "- prod", false),
                    ChatMessage.user("Continue"));
            new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                    .stream(new ChatRequest("m", "", messages, List.of(), 1000, null), t -> { }, new CancellationToken());

            JsonObject body = server.lastJson();
            assertFalse(body.has("system"));
            assertEquals(1000, body.get("max_tokens").getAsInt());
            JsonArray sent = body.getAsJsonArray("messages");
            JsonArray assistant = sent.get(1).getAsJsonObject().getAsJsonArray("content");
            assertEquals("text", assistant.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("tool_use", assistant.get(1).getAsJsonObject().get("type").getAsString());
            // tool_result and the next user text are merged into one user message
            assertEquals(3, sent.size());
            JsonArray lastUser = sent.get(2).getAsJsonObject().getAsJsonArray("content");
            assertEquals("tool_result", lastUser.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("text", lastUser.get(1).getAsJsonObject().get("type").getAsString());
        }
    }

    @Test
    void retriesWithoutFallbackWhenRejected() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fallbacks: not supported for this model\"}}");
            server.enqueue(200, TEXT_STREAM);
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), true), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertEquals("Olá", turn.text());
            assertEquals(2, server.bodies.size());
            assertFalse(server.lastJson().has("fallbacks"));
        }
    }

    @Test
    void errorEventBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");
            LlmException e = assertThrows(LlmException.class,
                    () -> new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                            .stream(simpleRequest(), t -> { }, new CancellationToken()));
            assertTrue(e.getMessage().contains("Overloaded"));
        }
    }

    @Test
    void refusalStopReason() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM.replace("end_turn", "refusal"));
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertEquals(StopReason.REFUSAL, turn.stopReason());
        }
    }
}
