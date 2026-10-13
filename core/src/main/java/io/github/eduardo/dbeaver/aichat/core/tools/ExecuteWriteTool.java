package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier;

final class ExecuteWriteTool implements DbTool {

    private final DatabaseGateway gateway;

    ExecuteWriteTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "execute_write";
    }

    @Override
    public String description() {
        return "Run ONE SQL statement that modifies data or schema (INSERT, UPDATE, DELETE, DDL...). "
                + "The user must approve it in the chat before it runs.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Schema path from list_schemas (e.g. \\"mydb.public\\"). Selects the database the statement runs in; required when the target database is not the connection's default (on PostgreSQL each database is separate and cannot be queried from another). Still qualify table names with their schema."},
                  "sql":{"type":"string","description":"A single SQL statement."},
                  "reason":{"type":"string","description":"Short explanation shown to the user in the approval request."}
                },"required":["sql","reason"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String sql = Args.requireString(args, "sql");
        String reason = Args.optString(args, "reason");
        String schema = Args.optString(args, "schema");
        if (conn.readOnly()) {
            throw new ToolException("A conexão " + conn.name() + " está marcada como somente leitura no DBeaver; escrita bloqueada.");
        }
        if (SqlClassifier.statementCount(sql) > 1) {
            throw new ToolException("Envie um único comando SQL por chamada de execute_write.");
        }
        String target = schema == null ? conn.name() : conn.name() + " / " + schema;
        Decision decision = ctx.approvalGate().requestApproval(new ApprovalRequest(target, sql, reason), ctx.cancel());
        if (decision != Decision.APPROVED) {
            return ToolOutcome.error("O usuário rejeitou a execução. Não tente de novo sem perguntar ao usuário.");
        }
        long count = gateway.executeUpdate(conn.id(), schema, sql, ctx.cancel());
        return ToolOutcome.ok("Executado com sucesso na conexão " + conn.name() + ". Linhas afetadas: "
                + (count < 0 ? "não informado" : count));
    }
}
