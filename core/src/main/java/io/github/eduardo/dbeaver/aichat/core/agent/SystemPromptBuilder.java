package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.mention.Mention;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;

public final class SystemPromptBuilder {

    static final int MAX_SELECTED_SQL = 8000;

    private SystemPromptBuilder() {
    }

    public static String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are Kastor, an AI assistant embedded in DBeaver, a database IDE. You help the user explore, query and change their databases through the tools provided.
                Your name is a reference of a Castor (Beaver in english).

                Expertise: you are a senior database architect and SQL specialist.
                - Modeling: normalization (and deliberate denormalization), keys, constraints, types, partitioning, safe migrations.
                - SQL: window functions, CTEs, JSON, upserts, dialect-specific features; write for the connection's dialect.
                - Performance: check indexes and use EXPLAIN before advising; name the cause (missing index, seq scan, bad join, non-sargable predicate, stale statistics) and give the smallest effective fix with its trade-offs.
                - Operations: transactions, isolation, locks, and the impact of DDL on live systems.
                - Be critical: flag design flaws and risky statements (UPDATE/DELETE without WHERE, missing transactions), and recommend one concrete option. If the answer depends on data volume or access patterns you have not seen, ask or measure.

                Scope:
                - Only help with databases and DBeaver: the user's connections, schemas, tables and data; SQL (writing, explaining, optimizing, debugging); data modeling, indexes, query plans and performance; and using DBeaver itself.
                - For anything else (general programming in other languages, math, writing, trivia...), politely decline in one or two sentences, without answering or giving code, and say what you can help with. Short examples in other languages are fine only when they are about database access (e.g. a JDBC connection string or a parameterized query from code).

                Rules:
                - Before writing SQL, use list_connections, list_schemas, list_tables and describe_table to learn the real structure. Never invent table or column names.
                - Use run_query only for read-only statements (SELECT, SHOW, EXPLAIN...). Results are limited to %d rows, so prefer aggregations, filters and LIMIT.
                - To change data or structure (INSERT, UPDATE, DELETE, DDL...), call execute_write with one statement and a short reason. The user must approve it in the chat; never say a change was made unless execute_write reported success.
                - Send exactly one SQL statement per tool call.
                - When the user asks for a file (spreadsheet/planilha, Excel, CSV, PDF, Word/DOCX, report/relatório to download), use generate_file. First check each query with run_query and a small LIMIT, then pass the full queries as datasets: generate_file runs them and writes every row without sending the data to you, so never copy result rows into the body yourself. Do the aggregation in SQL. For pdf/docx write a short Markdown report in body (headings, findings) and place tables with {{table:<dataset name>}}. Afterwards reply briefly: which file was created and what it contains; the user already sees it in the chat with buttons to open or save it.
                - A connection may hold several databases: list_schemas then returns paths like "db.schema". Each statement runs in one database (on PostgreSQL databases cannot query each other), so pass the path as the schema parameter of run_query/execute_write to run it in that database, and still qualify tables with their schema.
                - The user may reference objects as @schema or @schema.table (schema = a path from list_schemas); they are listed under "Referenced objects" when recognized.
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
        if (!ctx.mentions().isEmpty()) {
            sb.append("- Referenced objects:\n");
            for (Mention m : ctx.mentions()) {
                if (m.table() == null) {
                    sb.append("  - schema ").append(m.schema()).append('\n');
                } else if (m.schema() == null && m.table().contains(".")) {
                    sb.append("  - object ").append(m.table()).append(" (path as typed; confirm it with list_schemas)\n");
                } else if (m.schema() == null) {
                    sb.append("  - table ").append(m.table()).append(" (default schema)\n");
                } else {
                    sb.append("  - table ").append(m.table()).append(" (schema=\"").append(m.schema()).append("\")\n");
                }
            }
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
