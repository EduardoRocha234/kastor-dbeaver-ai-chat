package io.github.eduardo.dbeaver.aichat.core.audit;

/** Destination for audit events. Implementations must never throw: auditing cannot break the chat. */
public interface AuditLog {

    AuditLog NONE = event -> { };

    void record(AuditEvent event);
}
