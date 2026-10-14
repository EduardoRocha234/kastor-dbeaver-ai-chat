package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;

public record ToolContext(String defaultConnectionId, ApprovalGate approvalGate, CancellationToken cancel,
        ToolLimits limits, ToolPolicy policy, ExportSettings exports) {

    public ToolContext(String defaultConnectionId, ApprovalGate approvalGate, CancellationToken cancel,
            ToolLimits limits, ToolPolicy policy) {
        this(defaultConnectionId, approvalGate, cancel, limits, policy, ExportSettings.NONE);
    }

    public ToolContext(String defaultConnectionId, ApprovalGate approvalGate, CancellationToken cancel,
            ToolLimits limits) {
        this(defaultConnectionId, approvalGate, cancel, limits, ToolPolicy.DEFAULT);
    }
}
