package io.github.eduardo.dbeaver.aichat.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatSessionHistoryTest {

    @Test
    void truncatesOnlyToolResultsFromEarlierQuestions() {
        String big = "x".repeat(5000);
        List<ChatMessage> out = ChatSession.compactHistory(List.of(
                ChatMessage.user("q1"), ChatMessage.toolResult("a", big, false),
                ChatMessage.user("q2"), ChatMessage.toolResult("b", big, false)));
        assertTrue(out.get(1).text().length() < 1100);
        assertTrue(out.get(1).text().endsWith("[... truncado]"));
        assertEquals(5000, out.get(3).text().length());
    }

    @Test
    void explainsRateLimits() {
        String msg = ChatSession.friendly(new LlmException("HTTP 413 de x: rate_limit_exceeded", 413));
        assertTrue(msg.startsWith("Limite de uso"));
        assertTrue(msg.contains("rate_limit_exceeded"));
        assertEquals("HTTP 400 de x: bad", ChatSession.friendly(new LlmException("HTTP 400 de x: bad", 400)));
    }
}
