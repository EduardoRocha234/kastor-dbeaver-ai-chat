package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;

public record ToolContext(String defaultConnectionId, ApprovalGate approvalGate, CancellationToken cancel,
        ToolLimits limits) {
}
