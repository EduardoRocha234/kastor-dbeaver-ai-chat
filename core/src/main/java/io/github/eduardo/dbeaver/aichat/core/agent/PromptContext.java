package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.mention.Mention;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import java.util.List;

/** Everything the system prompt mentions. Any field except maxRows and mentions may be null. */
public record PromptContext(ConnectionInfo connection, String dialect, String defaultSchema, String selectedSql,
        int maxRows, List<Mention> mentions) {
}
