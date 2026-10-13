package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;

final class ListConnectionsTool implements DbTool {

    private final DatabaseGateway gateway;

    ListConnectionsTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "list_connections";
    }

    @Override
    public String description() {
        return "List the database connections configured in DBeaver, with id, driver, connection state and read-only flag.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) {
        List<ConnectionInfo> all = gateway.listConnections();
        if (all.isEmpty()) {
            return ToolOutcome.ok("Nenhuma conexão configurada no DBeaver.");
        }
        StringBuilder sb = new StringBuilder("Conexões (").append(all.size()).append("):\n");
        for (ConnectionInfo c : all) {
            sb.append("- ").append(c.name()).append(" (id: ").append(c.id())
                    .append(", driver: ").append(c.driver())
                    .append(", projeto: ").append(c.project())
                    .append(c.connected() ? ", conectada" : ", desconectada")
                    .append(c.readOnly() ? ", somente leitura" : "")
                    .append(")\n");
        }
        return ToolOutcome.ok(sb.toString());
    }
}
