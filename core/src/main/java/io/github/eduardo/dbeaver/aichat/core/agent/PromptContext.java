package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;

/** Everything the system prompt mentions. Any field except maxRows may be null. */
public record PromptContext(ConnectionInfo connection, String dialect, String defaultSchema, String selectedSql,
        int maxRows) {
}
