package io.github.eduardo.dbeaver.aichat.core.tools;

public record ApprovalRequest(String connectionName, String sql, String reason) {
}
