package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;

final class ListTablesTool implements DbTool {

    private final DatabaseGateway gateway;

    ListTablesTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "list_tables";
    }

    @Override
    public String description() {
        return "List tables and views in a schema.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Schema path as returned by list_schemas (e.g. public or mydb.dbo). Omit to use the default schema."},
                  "filter":{"type":"string","description":"Case-insensitive substring of the table name."}
                },"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String schema = Args.optString(args, "schema");
        String filter = Args.optString(args, "filter");
        int limit = ctx.limits().maxListItems();
        List<TableInfo> tables = gateway.listTables(conn.id(), schema, filter, limit + 1);
        String where = conn.name() + (schema != null ? " / " + schema : "");
        if (tables.isEmpty()) {
            return ToolOutcome.ok("Nenhuma tabela encontrada em " + where + ".");
        }
        StringBuilder sb = new StringBuilder("Tabelas em ").append(where).append(":\n");
        for (int i = 0; i < Math.min(limit, tables.size()); i++) {
            TableInfo t = tables.get(i);
            sb.append("- ").append(t.name()).append(" [").append(t.type()).append("]\n");
        }
        if (tables.size() > limit) {
            sb.append("... mais resultados omitidos; use o parâmetro filter para refinar.\n");
        }
        return ToolOutcome.ok(sb.toString());
    }
}
