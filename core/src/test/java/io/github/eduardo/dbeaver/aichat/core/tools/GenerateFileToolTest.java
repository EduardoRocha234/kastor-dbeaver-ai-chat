package io.github.eduardo.dbeaver.aichat.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.audit.AuditEvent;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateFileToolTest {

    @TempDir
    Path dir;

    private FakeGateway gateway;
    private ToolRegistry registry;
    private final List<GeneratedFile> files = new ArrayList<>();
    private final List<AuditEvent> audit = new ArrayList<>();
    private ColumnMasker masker = ColumnMasker.NONE;
    private CancellationToken token;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        registry = ToolRegistry.standard(gateway);
        token = new CancellationToken();
    }

    private ToolOutcome call(String args) {
        ToolContext ctx = new ToolContext("pg-1", (r, c) -> Decision.APPROVED, token,
                new ToolLimits(200, 16000, 200), new ToolPolicy(true, masker, audit::add),
                new ExportSettings(dir, 1000, files::add));
        return registry.execute(new ToolCall("c1", "generate_file", args), ctx);
    }

    private List<Path> written() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    @Test
    void writesCsvAndReportsOnlyMetadata() throws Exception {
        ToolOutcome out = call("""
                {"format":"csv","filename":"clientes","datasets":[{"name":"c","sql":"select id, name from customers"}]}""");
        assertFalse(out.error(), out.content());
        Path file = dir.resolve("clientes.csv");
        assertTrue(Files.exists(file));
        String csv = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals("﻿id,name\r\n1,Ana\r\n2,\r\n", csv);
        assertFalse(out.content().contains("Ana"), out.content());
        assertTrue(out.content().contains("clientes.csv"), out.content());
        assertEquals(1, files.size());
        assertEquals(2, files.get(0).rows());
        assertEquals(1000, gateway.lastMaxRows);
        assertEquals("executed", audit.get(0).outcome());
    }

    @Test
    void resultCanBeReadBackToRestoreTheFileCard() {
        call("""
                {"format":"xlsx","filename":"vendas (x)","datasets":[{"name":"a","sql":"select 1"}]}""");
        ToolOutcome second = call("""
                {"format":"xlsx","filename":"vendas (x)","datasets":[{"name":"a","sql":"select 1"}]}""");
        GenerateFileResult parsed = GenerateFileResult.parse(second.content()).orElseThrow();
        assertEquals("vendas (x) (2).xlsx", parsed.fileName());
        assertEquals("xlsx", parsed.format());
        assertEquals(2, parsed.rows());
        assertTrue(GenerateFileResult.parse("Erro: algo").isEmpty());
        assertTrue(GenerateFileResult.parse(null).isEmpty());
    }

    @Test
    void blocksWritesBeforeRunningAnything() throws Exception {
        ToolOutcome out = call("""
                {"format":"xlsx","filename":"x","datasets":[{"name":"a","sql":"select 1"},
                {"name":"b","sql":"delete from customers"}]}""");
        assertTrue(out.error());
        assertTrue(gateway.reads.isEmpty());
        assertTrue(written().isEmpty());
        assertEquals("blocked", audit.get(0).outcome());
    }

    @Test
    void blocksWhenNativeParserDisagrees() {
        gateway.nativeKind = Optional.of(SqlKind.WRITE);
        assertTrue(call("""
                {"format":"csv","filename":"x","datasets":[{"name":"a","sql":"select f()"}]}""").error());
    }

    @Test
    void csvNeedsExactlyOneDataset() {
        ToolOutcome out = call("""
                {"format":"csv","filename":"x","datasets":[{"name":"a","sql":"select 1"},{"name":"b","sql":"select 2"}]}""");
        assertTrue(out.error());
        assertTrue(out.content().contains("xlsx"), out.content());
    }

    @Test
    void filenameCannotEscapeTheFolder() throws Exception {
        ToolOutcome out = call("""
                {"format":"csv","filename":"../../etc/passwd","datasets":[{"name":"a","sql":"select 1"}]}""");
        assertFalse(out.error(), out.content());
        assertEquals(List.of(dir.resolve("passwd.csv")), written());
    }

    @Test
    void doesNotOverwriteExistingFiles() throws Exception {
        String args = """
                {"format":"csv","filename":"r","datasets":[{"name":"a","sql":"select 1"}]}""";
        call(args);
        call(args);
        assertTrue(Files.exists(dir.resolve("r.csv")));
        assertTrue(Files.exists(dir.resolve("r (2).csv")));
    }

    @Test
    void masksColumns() throws Exception {
        masker = ColumnMasker.parse("name");
        call("""
                {"format":"csv","filename":"m","datasets":[{"name":"a","sql":"select 1"}]}""");
        String csv = Files.readString(dir.resolve("m.csv"));
        assertTrue(csv.contains("1,***"), csv);
        assertFalse(csv.contains("Ana"));
        assertEquals(List.of("name"), audit.get(0).maskedColumns());
    }

    @Test
    void reportsTruncation() {
        gateway.typedResult = new TypedResult(List.of("id"), List.of(List.of(1)), true);
        ToolOutcome out = call("""
                {"format":"xlsx","filename":"t","datasets":[{"name":"a","sql":"select 1"}]}""");
        assertTrue(out.content().contains("LIMITE"), out.content());
    }

    @Test
    void unknownPlaceholderIsAnError() throws Exception {
        ToolOutcome out = call("""
                {"format":"pdf","filename":"p","body":"# R\\n\\n{{table:nope}}","datasets":[{"name":"a","sql":"select 1"}]}""");
        assertTrue(out.error());
        assertTrue(out.content().contains("nope"), out.content());
        assertTrue(written().isEmpty());
    }

    @Test
    void writesEveryFormat() throws Exception {
        for (String f : List.of("xlsx", "pdf", "docx")) {
            ToolOutcome out = call("""
                    {"format":"%s","filename":"rel","title":"Relatório","body":"Texto **forte**.\\n\\n{{table:a}}",
                    "datasets":[{"name":"a","sql":"select 1"}]}""".formatted(f));
            assertFalse(out.error(), out.content());
            assertTrue(Files.size(dir.resolve("rel." + f)) > 0);
        }
    }

    @Test
    void cancellationLeavesNoFile() throws Exception {
        token.cancel();
        assertThrows(RuntimeException.class, () -> call("""
                {"format":"csv","filename":"c","datasets":[{"name":"a","sql":"select 1"}]}"""));
        assertTrue(written().isEmpty());
    }

    @Test
    void disabledWithoutFolder() {
        ToolContext ctx = new ToolContext("pg-1", (r, c) -> Decision.APPROVED, token, new ToolLimits(200, 16000, 200));
        ToolOutcome out = registry.execute(new ToolCall("c1", "generate_file", """
                {"format":"csv","filename":"c","datasets":[{"name":"a","sql":"select 1"}]}"""), ctx);
        assertTrue(out.error());
    }
}
