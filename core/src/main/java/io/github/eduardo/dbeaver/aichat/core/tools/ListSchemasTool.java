package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;

final class ListSchemasTool implements DbTool {

    private final DatabaseGateway gateway;

    ListSchemasTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "list_schemas";
    }

    @Override
    public String description() {
        return "List catalogs/schemas of a connection (connects if needed). Returns paths usable as the schema parameter of other tools.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."}
                },"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        List<String> schemas = gateway.listSchemas(conn.id());
        if (schemas.isEmpty()) {
            return ToolOutcome.ok("A conexão " + conn.name() + " não usa schemas; chame list_tables sem o parâmetro schema.");
        }
        StringBuilder sb = new StringBuilder("Schemas em ").append(conn.name()).append(":\n");
        int limit = ctx.limits().maxListItems();
        for (int i = 0; i < Math.min(limit, schemas.size()); i++) {
            sb.append("- ").append(schemas.get(i)).append('\n');
        }
        if (schemas.size() > limit) {
            sb.append("... ").append(schemas.size() - limit).append(" schema(s) omitidos.\n");
        }
        return ToolOutcome.ok(sb.toString());
    }
}
