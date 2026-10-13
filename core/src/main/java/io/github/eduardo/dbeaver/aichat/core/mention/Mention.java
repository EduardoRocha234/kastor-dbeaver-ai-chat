package io.github.eduardo.dbeaver.aichat.core.mention;

/** An @reference in the user's message. schema null = default schema; table null = the whole schema. */
public record Mention(String schema, String table) {
}
