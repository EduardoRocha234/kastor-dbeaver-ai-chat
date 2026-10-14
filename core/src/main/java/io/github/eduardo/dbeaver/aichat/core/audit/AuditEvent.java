package io.github.eduardo.dbeaver.aichat.core.audit;

import java.time.Instant;
import java.util.List;

/**
 * One line of the audit log. Holds the SQL the model asked for, never the rows it got back.
 * outcome: executed | rejected (user said no) | blocked (policy) | error.
 */
public record AuditEvent(String time, String user, String tool, String connection, String schema, String sql,
        String outcome, Long rows, List<String> maskedColumns, String detail) {

    public static AuditEvent now(String tool, String connection, String schema, String sql, String outcome, Long rows,
            List<String> maskedColumns, String detail) {
        return new AuditEvent(Instant.now().toString(), System.getProperty("user.name"), tool, connection, schema, sql,
                outcome, rows, maskedColumns == null || maskedColumns.isEmpty() ? null : maskedColumns, detail);
    }
}
