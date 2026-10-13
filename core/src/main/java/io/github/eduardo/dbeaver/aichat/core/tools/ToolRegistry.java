package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {

    private final Map<String, DbTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<DbTool> list) {
        for (DbTool tool : list) {
            tools.put(tool.name(), tool);
        }
    }

    public static ToolRegistry standard(DatabaseGateway gateway) {
        return new ToolRegistry(List.of(
                new ListConnectionsTool(gateway),
                new ListSchemasTool(gateway),
                new ListTablesTool(gateway),
                new DescribeTableTool(gateway),
                new RunQueryTool(gateway),
                new ExecuteWriteTool(gateway)));
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(DbTool::spec).toList();
    }

    /** Never throws except CancelledException; every failure becomes an error outcome for the model. */
    public ToolOutcome execute(ToolCall call, ToolContext ctx) {
        DbTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolOutcome.error("Ferramenta desconhecida: " + call.name());
        }
        JsonObject args;
        try {
            String raw = call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson();
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("arguments must be a JSON object");
            }
            args = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("INVALID_JSON", String.valueOf(call.argumentsJson()));
            return ToolOutcome.error(err.toString());
        }
        try {
            return tool.execute(args, ctx);
        } catch (CancelledException e) {
            throw e;
        } catch (ToolException | GatewayException e) {
            return ToolOutcome.error(e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            // LinkageError: a DBeaver API that changed between versions; the chat must survive it.
            return ToolOutcome.error("Erro interno na ferramenta " + call.name() + ": " + e);
        }
    }
}
