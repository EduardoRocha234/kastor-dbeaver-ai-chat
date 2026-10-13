package io.github.eduardo.dbeaver.aichat.core.tools;

public record ToolOutcome(String content, boolean error) {

    public static ToolOutcome ok(String content) {
        return new ToolOutcome(content, false);
    }

    public static ToolOutcome error(String content) {
        return new ToolOutcome(content, true);
    }
}
