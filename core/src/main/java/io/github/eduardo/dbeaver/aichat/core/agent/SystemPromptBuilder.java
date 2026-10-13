package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;

public final class SystemPromptBuilder {

    static final int MAX_SELECTED_SQL = 8000;

    private SystemPromptBuilder() {
    }

    public static String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an AI assistant embedded in DBeaver, a database IDE. You help the user explore, query and change their databases through the tools provided.

                Rules:
                - Before writing SQL, use list_connections, list_schemas, list_tables and describe_table to learn the real structure. Never invent table or column names.
                - Use run_query only for read-only statements (SELECT, SHOW, EXPLAIN...). Results are limited to %d rows, so prefer aggregations, filters and LIMIT.
                - To change data or structure (INSERT, UPDATE, DELETE, DDL...), call execute_write with one statement and a short reason. The user must approve it in the chat; never say a change was made unless execute_write reported success.
                - Send exactly one SQL statement per tool call.
                - When you show SQL to the user, put it in a ```sql fenced code block.
                - Answer in the same language the user writes in.
                """.formatted(ctx.maxRows()));
        sb.append("\nContext:\n");
        ConnectionInfo c = ctx.connection();
        if (c == null) {
            sb.append("- No default connection is selected. Call list_connections and ask the user which one to use when it is ambiguous.\n");
        } else {
            sb.append("- Default connection: ").append(c.name()).append(" (id: ").append(c.id())
                    .append(", driver: ").append(c.driver()).append(")");
            if (c.readOnly()) {
                sb.append(" - marked READ-ONLY in DBeaver; writes will be refused");
            }
            sb.append('\n');
        }
        if (notBlank(ctx.dialect())) {
            sb.append("- SQL dialect: ").append(ctx.dialect()).append('\n');
        }
        if (notBlank(ctx.defaultSchema())) {
            sb.append("- Default schema: ").append(ctx.defaultSchema()).append('\n');
        }
        if (notBlank(ctx.selectedSql())) {
            String sql = ctx.selectedSql().length() > MAX_SELECTED_SQL
                    ? ctx.selectedSql().substring(0, MAX_SELECTED_SQL) + "\n-- [truncated]"
                    : ctx.selectedSql();
            sb.append("- SQL currently selected in the editor:\n```sql\n").append(sql).append("\n```\n");
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
