package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A chat thread. Serialized as-is by ConversationStore (Gson, field access). */
public final class Conversation {

    public static final String DEFAULT_TITLE = "Nova conversa";
    private static final int TITLE_LENGTH = 60;

    private String id;
    private String title;
    private long createdAt;
    private long updatedAt;
    private String providerId;
    private String connectionId;
    private List<ChatMessage> messages = new ArrayList<>();

    public static Conversation create() {
        Conversation c = new Conversation();
        c.id = UUID.randomUUID().toString();
        c.title = DEFAULT_TITLE;
        c.createdAt = System.currentTimeMillis();
        c.updatedAt = c.createdAt;
        return c;
    }

    public synchronized void add(ChatMessage message) {
        messages.add(message);
        updatedAt = System.currentTimeMillis();
        if (message.role() == Role.USER && DEFAULT_TITLE.equals(title)) {
            String t = message.text().strip().replaceAll("\\s+", " ");
            title = t.isEmpty() ? DEFAULT_TITLE : t.length() > TITLE_LENGTH ? t.substring(0, TITLE_LENGTH) + "…" : t;
        }
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String providerId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String connectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }
}
