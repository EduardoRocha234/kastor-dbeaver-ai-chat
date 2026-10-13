package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void nullTextAndToolCallsBecomeEmpty() {
        ChatMessage m = new ChatMessage(Role.ASSISTANT, null, null, null, false, null, null);
        assertEquals("", m.text());
        assertEquals(List.of(), m.toolCalls());
    }

    @Test
    void assistantFactoryCopiesTurn() {
        ToolCall call = new ToolCall("c1", "run_query", "{}");
        AssistantTurn turn = new AssistantTurn("oi", List.of(call), StopReason.TOOL_USE, null, null);
        ChatMessage m = ChatMessage.assistant(turn);
        assertEquals(Role.ASSISTANT, m.role());
        assertEquals("oi", m.text());
        assertEquals(List.of(call), m.toolCalls());
    }

    @Test
    void emptyTurnDetection() {
        assertTrue(new AssistantTurn("", List.of(), StopReason.END_TURN, null, null).isEmpty());
        assertFalse(new AssistantTurn("x", List.of(), StopReason.END_TURN, null, null).isEmpty());
    }
}
