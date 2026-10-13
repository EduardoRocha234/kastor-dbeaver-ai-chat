package io.github.eduardo.dbeaver.aichat.core.mention;

/**
 * One completion entry. path = what it names, type = "database", "schema" or the table type (TABLE, VIEW...);
 * replacement substitutes the "@query" being typed; drill = choosing it lists its children next.
 */
public record Suggestion(String path, String type, String replacement, boolean drill) {
}
