package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;

final class DescribeTableTool implements DbTool {

    private final DatabaseGateway gateway;

    DescribeTableTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "describe_table";
    }

    @Override
    public String description() {
        return "Describe a table: columns with types, nullability and defaults, primary key, foreign keys and indexes.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Schema path as returned by list_schemas. Omit to use the default schema."},
                  "table":{"type":"string","description":"Table or view name."}
                },"required":["table"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String table = Args.requireString(args, "table");
        TableDescription d = gateway.describeTable(conn.id(), Args.optString(args, "schema"), table);
        StringBuilder sb = new StringBuilder("Tabela ");
        if (d.schema() != null && !d.schema().isBlank()) {
            sb.append(d.schema()).append('.');
        }
        sb.append(d.name()).append(" (").append(d.type()).append(")\nColunas:\n");
        for (ColumnInfo c : d.columns()) {
            sb.append("- ").append(c.name()).append(' ').append(c.type()).append(c.nullable() ? " NULL" : " NOT NULL");
            if (c.defaultValue() != null && !c.defaultValue().isBlank() && !ctx.policy().masker().matches(c.name())) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            sb.append('\n');
        }
        if (!d.primaryKey().isEmpty()) {
            sb.append("Chave primária: ").append(String.join(", ", d.primaryKey())).append('\n');
        }
        if (!d.foreignKeys().isEmpty()) {
            sb.append("Chaves estrangeiras:\n");
            for (ForeignKeyInfo fk : d.foreignKeys()) {
                sb.append("- ").append(fk.name()).append(" (").append(String.join(", ", fk.columns())).append(") -> ")
                        .append(fk.referencedTable()).append(" (").append(String.join(", ", fk.referencedColumns())).append(")\n");
            }
        }
        if (!d.indexes().isEmpty()) {
            sb.append("Índices:\n");
            for (IndexInfo idx : d.indexes()) {
                sb.append("- ").append(idx.name()).append(idx.unique() ? " UNIQUE" : "")
                        .append(" (").append(String.join(", ", idx.columns())).append(")\n");
            }
        }
        return ToolOutcome.ok(sb.toString());
    }
}
