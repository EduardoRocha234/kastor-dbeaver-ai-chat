package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.audit.AuditEvent;
import io.github.eduardo.dbeaver.aichat.core.audit.AuditLog;
import java.util.List;

/** What the tools may do and what they must record. */
public record ToolPolicy(boolean allowWrites, ColumnMasker masker, AuditLog audit) {

    public static final ToolPolicy DEFAULT = new ToolPolicy(true, ColumnMasker.NONE, AuditLog.NONE);

    void audit(String tool, ConnectionInfo conn, String schema, String sql, String outcome, Long rows,
            List<String> maskedColumns, String detail) {
        audit.record(AuditEvent.now(tool, conn.name(), schema, sql, outcome, rows, maskedColumns, detail));
    }
}
