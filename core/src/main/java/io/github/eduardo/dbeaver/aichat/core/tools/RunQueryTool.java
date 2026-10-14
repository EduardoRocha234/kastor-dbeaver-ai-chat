package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;

final class RunQueryTool implements DbTool {

    private final DatabaseGateway gateway;

    RunQueryTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "run_query";
    }

    @Override
    public String description() {
        return "Run ONE read-only SQL statement (SELECT, SHOW, EXPLAIN...) and return up to max_rows rows as text. "
                + "Statements that modify data are rejected; use execute_write for those.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Schema path from list_schemas (e.g. \\"mydb.public\\"). Selects the database the statement runs in; required when the target database is not the connection's default (on PostgreSQL each database is separate and cannot be queried from another). Still qualify table names with their schema."},
                  "sql":{"type":"string","description":"A single read-only SQL statement."},
                  "max_rows":{"type":"integer","description":"Maximum rows to return (capped by the user's settings)."}
                },"required":["sql"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String sql = Args.requireString(args, "sql");
        boolean read = ReadSqlGuard.isRead(gateway, conn.id(), sql);
        ToolPolicy policy = ctx.policy();
        String schema = Args.optString(args, "schema");
        if (!read) {
            policy.audit(name(), conn, schema, sql, "blocked", null, null, "not confirmed read-only");
            throw new ToolException("Este SQL modifica dados ou não pôde ser confirmado como somente leitura. "
                    + "Use execute_write (exige aprovação do usuário) ou reescreva como um único SELECT.");
        }
        Integer requested = Args.optInt(args, "max_rows");
        int maxRows = ctx.limits().maxRows();
        if (requested != null && requested > 0) {
            maxRows = Math.min(requested, maxRows);
        }
        QueryResult raw;
        try {
            raw = gateway.runReadQuery(conn.id(), schema, sql, maxRows, ctx.cancel());
        } catch (GatewayException e) {
            policy.audit(name(), conn, schema, sql, "error", null, null, e.getMessage());
            throw e;
        }
        QueryResult result = policy.masker().apply(raw);
        policy.audit(name(), conn, schema, sql, "executed", (long) raw.rows().size(),
                policy.masker().maskedColumns(raw.columns()), null);
        String header = result.rows().size() + " linha(s)"
                + (result.truncated() ? " (limite de " + maxRows + " atingido; pode haver mais)" : "") + "\n";
        return ToolOutcome.ok(header + TextTable.format(result, ctx.limits().maxResultChars()));
    }
}
