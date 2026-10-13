package io.github.eduardo.dbeaver.aichat.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.mention.Mention;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemPromptBuilderTest {

    @Test
    void includesConnectionDialectSchemaAndSelection() {
        ConnectionInfo conn = new ConnectionInfo("pg-1", "prod-pg", "PostgreSQL", "General", true, true);
        String prompt = SystemPromptBuilder.build(new PromptContext(conn, "PostgreSQL", "public", "select * from orders", 200, List.of()));
        assertTrue(prompt.contains("prod-pg (id: pg-1, driver: PostgreSQL)"));
        assertTrue(prompt.contains("READ-ONLY"));
        assertTrue(prompt.contains("SQL dialect: PostgreSQL"));
        assertTrue(prompt.contains("Default schema: public"));
        assertTrue(prompt.contains("```sql\nselect * from orders\n```"));
        assertTrue(prompt.contains("limited to 200 rows"));
    }

    @Test
    void withoutConnectionAsksToList() {
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, null, 50, List.of()));
        assertTrue(prompt.contains("No default connection"));
        assertFalse(prompt.contains("SQL dialect"));
    }

    @Test
    void truncatesHugeSelection() {
        String huge = "x".repeat(20_000);
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, huge, 50, List.of()));
        assertTrue(prompt.contains("-- [truncated]"));
        assertTrue(prompt.length() < 12_000);
    }

    @Test
    void explainsHowToReachOtherDatabases() {
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, null, 50, List.of()));
        assertTrue(prompt.contains("schema parameter of run_query"), prompt);
    }

    @Test
    void listsMentionedObjectsWithTheSchemaToUse() {
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, null, 50, List.of(
                new Mention("base_dev.public", "perfil"), new Mention("login_dev.public", null), new Mention(null, "orders"),
                new Mention(null, "base_dev.public.perfil"))));
        assertTrue(prompt.contains("- object base_dev.public.perfil (path as typed"), prompt);
        assertTrue(prompt.contains("- table perfil (schema=\"base_dev.public\")"), prompt);
        assertTrue(prompt.contains("- schema login_dev.public"), prompt);
        assertTrue(prompt.contains("- table orders (default schema)"), prompt);
    }

    @Test
    void restrictsScopeToDatabasesAndDbeaver() {
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, null, 50, List.of()));
        assertTrue(prompt.contains("Scope:"), prompt);
        assertTrue(prompt.contains("politely decline"), prompt);
    }
}
