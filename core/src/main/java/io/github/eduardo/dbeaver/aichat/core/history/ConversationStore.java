package io.github.eduardo.dbeaver.aichat.core.history;

import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.agent.Conversation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One JSON file per conversation: {dir}/{uuid}.json */
public final class ConversationStore {

    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Path dir;

    public ConversationStore(Path dir) {
        this.dir = dir;
    }

    public void save(Conversation conversation) throws IOException {
        Files.createDirectories(dir);
        Path target = file(conversation.id());
        Path tmp = dir.resolve(conversation.id() + ".json.tmp");
        String json;
        synchronized (conversation) {
            json = Json.GSON.toJson(conversation);
        }
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Conversation load(String id) throws IOException {
        return read(file(id));
    }

    public List<ConversationSummary> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ConversationSummary> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) {
                try {
                    Conversation c = read(p);
                    if (c != null && c.id() != null) {
                        out.add(new ConversationSummary(c.id(), c.title(), c.updatedAt()));
                    }
                } catch (IOException | RuntimeException e) {
                    // corrupt or foreign file: not listed
                }
            }
        }
        out.sort(Comparator.comparingLong(ConversationSummary::updatedAt).reversed());
        return out;
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Id de conversa inválido: " + id);
        }
        return dir.resolve(id + ".json");
    }

    private static Conversation read(Path p) throws IOException {
        return Json.GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Conversation.class);
    }
}
