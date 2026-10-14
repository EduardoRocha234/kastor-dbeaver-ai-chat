package io.github.eduardo.dbeaver.aichat.core.tools;

public record ToolLimits(int maxRows, int maxResultChars, int maxListItems) {

    public static final ToolLimits DEFAULT = new ToolLimits(200, 12000, 200);
}
