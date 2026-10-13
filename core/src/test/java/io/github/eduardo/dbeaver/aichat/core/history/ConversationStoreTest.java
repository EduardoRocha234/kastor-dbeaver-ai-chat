package io.github.eduardo.dbeaver.aichat.core.history;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.agent.Conversation;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

    @TempDir
    Path dir;

    private static Conversation sample(String question) {
        Conversation c = Conversation.create();
        c.setProviderId("p1");
        c.setConnectionId("pg-1");
        c.add(ChatMessage.user(question));
        JsonArray raw = new JsonArray();
        raw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"abc\"}"));
        c.add(ChatMessage.assistant(new AssistantTurn("", List.of(new ToolCall("t1", "run_query", "{\"sql\":\"select 1\"}")),
                StopReason.TOOL_USE, raw, "anthropic")));
        c.add(ChatMessage.toolResult("t1", "1 linha(s)", false));
        return c;
    }

    @Test
    void roundTripKeepsMessagesAndRawContent() throws Exception {
        ConversationStore store = new ConversationStore(dir);
        Conversation original = sample("Quantos pedidos? Ação ✓");
        store.save(original);

        Conversation loaded = store.load(original.id());
        assertEquals(original.title(), loaded.title());
        assertEquals("p1", loaded.providerId());
        assertEquals("pg-1", loaded.connectionId());
        assertEquals(original.messages(), loaded.messages());
        assertEquals("abc", loaded.messages().get(1).rawContent().get(0).getAsJsonObject().get("signature").getAsString());
    }

    @Test
    void listIsSortedNewestFirstAndSkipsCorruptFiles() throws Exception {
        ConversationStore store = new ConversationStore(dir);
        Conversation older = sample("primeira");
        store.save(older);
        Thread.sleep(5);
        Conversation newer = sample("segunda");
        store.save(newer);
        Files.writeString(dir.resolve("00000000-0000-0000-0000-000000000000.json"), "{not json");

        List<ConversationSummary> list = store.list();
        assertEquals(List.of(newer.id(), older.id()), list.stream().map(ConversationSummary::id).toList());
        assertEquals("segunda", list.get(0).title());
    }

    @Test
    void deleteRemovesFile() throws Exception {
        ConversationStore store = new ConversationStore(dir);
        Conversation c = sample("x");
        store.save(c);
        store.delete(c.id());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void rejectsIdsThatAreNotUuids() {
        ConversationStore store = new ConversationStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.load("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("..\\x"));
    }

    @Test
    void listOnMissingDirectoryIsEmpty() throws Exception {
        assertTrue(new ConversationStore(dir.resolve("nao-existe")).list().isEmpty());
    }
}
