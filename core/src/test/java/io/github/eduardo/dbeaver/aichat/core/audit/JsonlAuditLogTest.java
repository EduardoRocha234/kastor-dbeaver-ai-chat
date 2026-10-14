package io.github.eduardo.dbeaver.aichat.core.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlAuditLogTest {

    @TempDir
    Path dir;

    @Test
    void appendsOneJsonLinePerEventAndKeepsSqlOnOneLine() throws IOException {
        Path file = dir.resolve("sub/audit.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, 1_000_000);
        log.record(AuditEvent.now("run_query", "pg", null, "select 1\nfrom t", "executed", 1L, List.of("cpf"), null));
        log.record(AuditEvent.now("execute_write", "pg", "public", "delete from t", "rejected", null, null, "limpar"));
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"sql\":\"select 1\\nfrom t\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"maskedColumns\":[\"cpf\"]"), lines.get(0));
        assertTrue(lines.get(1).contains("\"outcome\":\"rejected\""), lines.get(1));
    }

    @Test
    void rollsOverWhenTooBig() throws IOException {
        Path file = dir.resolve("audit.jsonl");
        JsonlAuditLog log = new JsonlAuditLog(file, 10);
        log.record(AuditEvent.now("run_query", "pg", null, "select 1", "executed", 1L, null, null));
        log.record(AuditEvent.now("run_query", "pg", null, "select 2", "executed", 1L, null, null));
        assertTrue(Files.exists(dir.resolve("audit.jsonl.1")));
        assertEquals(1, Files.readAllLines(file).size());
    }

    @Test
    void neverThrowsOnUnwritablePath() throws IOException {
        Files.writeString(dir.resolve("a-file"), "x");
        JsonlAuditLog log = new JsonlAuditLog(dir.resolve("a-file/x/audit.jsonl"), 100);
        assertDoesNotThrow(() -> log.record(AuditEvent.now("run_query", "pg", null, "select 1", "executed", 1L, null, null)));
    }
}
