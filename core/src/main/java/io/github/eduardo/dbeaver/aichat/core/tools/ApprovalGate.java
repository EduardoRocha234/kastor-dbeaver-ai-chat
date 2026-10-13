package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;

public interface ApprovalGate {

    /**
     * Blocks until the user decides.
     *
     * @throws io.github.eduardo.dbeaver.aichat.core.CancelledException when cancelled while waiting
     */
    Decision requestApproval(ApprovalRequest request, CancellationToken cancel);
}
