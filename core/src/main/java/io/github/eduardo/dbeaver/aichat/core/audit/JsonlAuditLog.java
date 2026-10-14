package io.github.eduardo.dbeaver.aichat.core.audit;

import io.github.eduardo.dbeaver.aichat.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Appends one JSON object per line; rolls the file over to {@code <name>.1} once it passes maxBytes. */
public final class JsonlAuditLog implements AuditLog {

    private final Path file;
    private final long maxBytes;

    public JsonlAuditLog(Path file, long maxBytes) {
        this.file = file;
        this.maxBytes = maxBytes;
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void record(AuditEvent event) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file) && Files.size(file) > maxBytes) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(file, Json.GSON.toJson(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // Best effort by design.
        }
    }
}
