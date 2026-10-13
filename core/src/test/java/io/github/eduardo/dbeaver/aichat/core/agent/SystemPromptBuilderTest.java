package io.github.eduardo.dbeaver.aichat.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import org.junit.jupiter.api.Test;

class SystemPromptBuilderTest {

    @Test
    void includesConnectionDialectSchemaAndSelection() {
        ConnectionInfo conn = new ConnectionInfo("pg-1", "prod-pg", "PostgreSQL", "General", true, true);
        String prompt = SystemPromptBuilder.build(new PromptContext(conn, "PostgreSQL", "public", "select * from orders", 200));
        assertTrue(prompt.contains("prod-pg (id: pg-1, driver: PostgreSQL)"));
        assertTrue(prompt.contains("READ-ONLY"));
        assertTrue(prompt.contains("SQL dialect: PostgreSQL"));
        assertTrue(prompt.contains("Default schema: public"));
        assertTrue(prompt.contains("```sql\nselect * from orders\n```"));
        assertTrue(prompt.contains("limited to 200 rows"));
    }

    @Test
    void withoutConnectionAsksToList() {
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, null, 50));
        assertTrue(prompt.contains("No default connection"));
        assertFalse(prompt.contains("SQL dialect"));
    }

    @Test
    void truncatesHugeSelection() {
        String huge = "x".repeat(20_000);
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, huge, 50));
        assertTrue(prompt.contains("-- [truncated]"));
        assertTrue(prompt.length() < 12_000);
    }
}
