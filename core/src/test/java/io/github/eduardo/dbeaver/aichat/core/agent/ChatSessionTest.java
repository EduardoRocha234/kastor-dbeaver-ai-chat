package io.github.eduardo.dbeaver.aichat.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import io.github.eduardo.dbeaver.aichat.core.llm.Role;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.DbTool;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolContext;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolLimits;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ChatSessionTest {

    private static final ProviderConfig CONFIG =
            new ProviderConfig("p", "p", ProviderType.OPENAI_COMPATIBLE, "http://x", "m", 0, null, false);

    private static DbTool probe(Function<ToolContext, ToolOutcome> body) {
        return new DbTool() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public JsonObject inputSchema() {
                return Json.parseObject("{\"type\":\"object\",\"properties\":{}}");
            }

            @Override
            public ToolOutcome execute(JsonObject args, ToolContext ctx) {
                return body.apply(ctx);
            }
        };
    }

    private static AssistantTurn toolTurn(String... ids) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(new ToolCall(id, "probe", "{}"));
        }
        return new AssistantTurn("", calls, StopReason.TOOL_USE, null, null);
    }

    private static AssistantTurn text(String t) {
        return new AssistantTurn(t, List.of(), StopReason.END_TURN, null, null);
    }

    private static ToolContext ctx(CancellationToken token) {
        return new ToolContext("db", (r, c) -> Decision.APPROVED, token, ToolLimits.DEFAULT);
    }

    private static List<Role> roles(Conversation c) {
        return c.messages().stream().map(ChatMessage::role).toList();
    }

    @Test
    void finalAnswerWithoutTools() {
        Conversation conversation = Conversation.create();
        ScriptedProvider provider = new ScriptedProvider().then(text("Olá!"));
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of()), SessionSettings.DEFAULT)
                .runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertEquals(List.of(Role.USER, Role.ASSISTANT), roles(conversation));
        assertEquals(List.of("start", "text:Olá!", "end:END_TURN", "finished"), listener.events);
        assertEquals("sys", provider.requests.get(0).systemPrompt());
        assertEquals("Oi", conversation.title());
    }

    @Test
    void runsToolsAndLoopsUntilFinalAnswer() {
        Conversation conversation = Conversation.create();
        ScriptedProvider provider = new ScriptedProvider().then(toolTurn("t1")).then(text("Pronto"));
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of(probe(c -> ToolOutcome.ok("42")))),
                SessionSettings.DEFAULT).runTurn("Conte", "sys", ctx(new CancellationToken()), listener);

        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), roles(conversation));
        ChatMessage result = conversation.messages().get(2);
        assertEquals("t1", result.toolCallId());
        assertEquals("42", result.text());
        assertEquals(2, provider.requests.size());
        assertEquals(3, provider.requests.get(1).messages().size());
        assertEquals("probe", provider.requests.get(0).tools().get(0).name());
        assertTrue(listener.events.contains("tool:probe"));
        assertTrue(listener.events.contains("toolEnd:probe"));
    }

    @Test
    void doesNotRunToolsWhenResponseWasTruncated() {
        Conversation conversation = Conversation.create();
        AssistantTurn truncated = new AssistantTurn("", List.of(new ToolCall("t1", "probe", "{\"a\":")),
                StopReason.MAX_TOKENS, null, null);
        List<String> executed = new ArrayList<>();
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, new ScriptedProvider().then(truncated), CONFIG,
                new ToolRegistry(List.of(probe(c -> {
                    executed.add("x");
                    return ToolOutcome.ok("");
                }))), SessionSettings.DEFAULT)
                .runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertTrue(executed.isEmpty());
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL), roles(conversation));
        assertTrue(conversation.messages().get(2).error());
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("notice:") && e.contains("max_tokens")));
    }

    @Test
    void stopsAtIterationLimit() {
        Conversation conversation = Conversation.create();
        ScriptedProvider provider = new ScriptedProvider();
        provider.repeat = toolTurn("t");
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of(probe(c -> ToolOutcome.ok("ok")))),
                new SessionSettings(3)).runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertEquals(3, provider.requests.size());
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("notice:Limite de 3")));
        assertEquals("finished", listener.events.get(listener.events.size() - 1));
    }

    @Test
    void cancellationDuringToolsClosesEveryPendingCall() {
        Conversation conversation = Conversation.create();
        CancellationToken token = new CancellationToken();
        ScriptedProvider provider = new ScriptedProvider().then(toolTurn("t1", "t2", "t3"));
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of(probe(c -> {
            c.cancel().cancel();
            return ToolOutcome.ok("feito");
        }))), SessionSettings.DEFAULT).runTurn("Oi", "sys", ctx(token), listener);

        List<ChatMessage> results = conversation.messages().stream().filter(m -> m.role() == Role.TOOL).toList();
        assertEquals(List.of("t1", "t2", "t3"), results.stream().map(ChatMessage::toolCallId).toList());
        assertEquals("feito", results.get(0).text());
        assertTrue(results.get(1).error());
        assertTrue(listener.events.contains("notice:Cancelado."));
        assertEquals(1, provider.requests.size());
    }

    @Test
    void providerErrorIsReportedAndUserMessageKept() {
        Conversation conversation = Conversation.create();
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, new ScriptedProvider().thenFail(new LlmException("HTTP 401: chave inválida", 401)),
                CONFIG, new ToolRegistry(List.of()), SessionSettings.DEFAULT)
                .runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertEquals(List.of(Role.USER), roles(conversation));
        assertTrue(listener.events.contains("error:HTTP 401: chave inválida"));
        assertEquals("finished", listener.events.get(listener.events.size() - 1));
    }
}
