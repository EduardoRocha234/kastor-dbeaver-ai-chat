package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolSpec;

public interface DbTool {

    String name();

    String description();

    JsonObject inputSchema();

    ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException;

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema());
    }
}
