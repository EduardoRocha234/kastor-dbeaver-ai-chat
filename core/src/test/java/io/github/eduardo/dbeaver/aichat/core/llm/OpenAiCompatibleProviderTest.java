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

class OpenAiCompatibleProviderTest {

    private static ProviderConfig config(String baseUrl) {
        return new ProviderConfig("p1", "Teste", ProviderType.OPENAI_COMPATIBLE, baseUrl, "gpt-test", 0, null, false);
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest("gpt-test", "Você é um assistente.", List.of(ChatMessage.user("Oi")), List.of(), 0, null);
    }

    @Test
    void streamsTextAndSendsBearerToken() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    data: {"choices":[{"delta":{"role":"assistant","content":"Olá"}}]}

                    data: {"choices":[{"delta":{"content":" mundo"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                    config(server.baseUrl() + "/v1/"), "sk-test", HttpStreaming.defaultClient());
            List<String> deltas = new ArrayList<>();
            AssistantTurn turn = provider.stream(simpleRequest(), deltas::add, new CancellationToken());

            assertEquals(List.of("Olá", " mundo"), deltas);
            assertEquals("Olá mundo", turn.text());
            assertEquals(StopReason.END_TURN, turn.stopReason());
            assertEquals("/v1/chat/completions", server.lastPath);
            assertEquals("Bearer sk-test", server.lastHeaders.getFirst("Authorization"));
        }
    }

    @Test
    void omitsAuthorizationWhenKeyIsBlank() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n");
            new OpenAiCompatibleProvider(config(server.baseUrl()), "", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertNull(server.lastHeaders.getFirst("Authorization"));
        }
    }

    @Test
    void assemblesToolCallsAcrossDeltas() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"run_query","arguments":""}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"sql\\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"select 1\\"}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """);
            AssistantTurn turn = new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());

            assertEquals(StopReason.TOOL_USE, turn.stopReason());
            assertEquals(1, turn.toolCalls().size());
            ToolCall call = turn.toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("run_query", call.name());
            assertEquals("select 1", JsonParser.parseString(call.argumentsJson()).getAsJsonObject().get("sql").getAsString());
        }
    }

    @Test
    void stopWithToolCallsIsTreatedAsToolUse() throws Exception {
        // Some local servers (e.g. Ollama) report finish_reason "stop" together with tool calls.
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_connections","arguments":"{}"}}]},"finish_reason":"stop"}]}

                    """);
            AssistantTurn turn = new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertEquals(StopReason.TOOL_USE, turn.stopReason());
            assertEquals("call_0", turn.toolCalls().get(0).id());
        }
    }

    @Test
    void mapsConversationToOpenAiFormat() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n");
            ToolCall call = new ToolCall("call_1", "list_tables", "{\"schema\":\"public\"}");
            JsonArray anthropicRaw = new JsonArray();
            anthropicRaw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}"));
            List<ChatMessage> messages = List.of(
                    ChatMessage.user("Quais tabelas?"),
                    ChatMessage.assistant(new AssistantTurn("", List.of(call), StopReason.TOOL_USE, null, null)),
                    ChatMessage.toolResult("call_1", "- orders [TABLE]", false),
                    new ChatMessage(Role.ASSISTANT, "Resposta anterior", List.of(), null, false, anthropicRaw, "anthropic"),
                    ChatMessage.user("E agora?"));
            ToolSpec spec = new ToolSpec("list_tables", "Lista tabelas", Json.parseObject("{\"type\":\"object\",\"properties\":{}}"));
            ChatRequest request = new ChatRequest("gpt-test", "sys", messages, List.of(spec), 0, 0.2);

            new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                    .stream(request, t -> { }, new CancellationToken());

            JsonObject body = server.lastJson();
            assertTrue(body.get("stream").getAsBoolean());
            assertFalse(body.has("max_tokens"));
            assertEquals(0.2, body.get("temperature").getAsDouble());
            JsonArray sent = body.getAsJsonArray("messages");
            assertEquals("system", sent.get(0).getAsJsonObject().get("role").getAsString());
            JsonObject assistant = sent.get(2).getAsJsonObject();
            assertTrue(assistant.get("content").isJsonNull());
            assertEquals("list_tables", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                    .getAsJsonObject("function").get("name").getAsString());
            JsonObject toolMsg = sent.get(3).getAsJsonObject();
            assertEquals("tool", toolMsg.get("role").getAsString());
            assertEquals("call_1", toolMsg.get("tool_call_id").getAsString());
            // Anthropic raw content is ignored: plain text is sent.
            assertEquals("Resposta anterior", sent.get(4).getAsJsonObject().get("content").getAsString());
            JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
            assertEquals("function", tool.get("type").getAsString());
            assertEquals("object", tool.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
        }
    }

    @Test
    void httpErrorBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(401, "{\"error\":{\"message\":\"Invalid API key\"}}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatibleProvider(config(server.baseUrl()), "bad", HttpStreaming.defaultClient())
                            .stream(simpleRequest(), t -> { }, new CancellationToken()));
            assertEquals(401, e.status());
            assertTrue(e.getMessage().contains("Invalid API key"));
        }
    }

    @Test
    void errorObjectInsideStreamBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "data: {\"error\":{\"message\":\"model not found\"}}\n\n");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                            .stream(simpleRequest(), t -> { }, new CancellationToken()));
            assertTrue(e.getMessage().contains("model not found"));
        }
    }
}
