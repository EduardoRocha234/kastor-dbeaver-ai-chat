package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicStreamAccumulatorTest {

    private static AssistantTurn run(String... events) throws Exception {
        AnthropicStreamAccumulator acc = new AnthropicStreamAccumulator(t -> { });
        for (String e : events) {
            acc.accept(e);
        }
        return acc.finish();
    }

    @Test
    void onlyToolCallsAfterLastFallbackBlockAreReturned() throws Exception {
        AssistantTurn turn = run(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_old\",\"name\":\"run_query\",\"input\":{}}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"fallback\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_new\",\"name\":\"run_query\",\"input\":{}}}",
                "{\"type\":\"content_block_stop\",\"index\":2}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}");
        assertEquals(1, turn.toolCalls().size());
        assertEquals("toolu_new", turn.toolCalls().get(0).id());
        assertEquals("{}", turn.toolCalls().get(0).argumentsJson());
    }

    @Test
    void invalidToolJsonIsReportedRawAndEchoedAsEmptyObject() throws Exception {
        AssistantTurn turn = run(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"run_query\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"sql\\\": \\\"sel\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}");
        assertEquals(StopReason.MAX_TOKENS, turn.stopReason());
        assertEquals("{\"sql\": \"sel", turn.toolCalls().get(0).argumentsJson());
        assertEquals(0, turn.rawContent().get(0).getAsJsonObject().getAsJsonObject("input").size());
    }

    @Test
    void echoDropsThinkingAndToolUseBeforeFallback() {
        JsonArray raw = new JsonArray();
        raw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}"));
        raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"old\",\"name\":\"x\",\"input\":{}}"));
        raw.add(Json.parseObject("{\"type\":\"text\",\"text\":\"parcial\"}"));
        raw.add(Json.parseObject("{\"type\":\"fallback\"}"));
        raw.add(Json.parseObject("{\"type\":\"text\",\"text\":\"final\"}"));
        raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"new\",\"name\":\"x\",\"input\":{}}"));
        List<String> types = new ArrayList<>();
        for (JsonElement e : AnthropicMapper.echoContent(raw)) {
            types.add(e.getAsJsonObject().get("type").getAsString());
        }
        assertEquals(List.of("text", "fallback", "text", "tool_use"), types);
    }
}
