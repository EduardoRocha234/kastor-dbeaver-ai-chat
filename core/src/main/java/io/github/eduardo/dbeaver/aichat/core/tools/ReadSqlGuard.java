package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;

/** The read-only check shared by every tool that runs model-written SQL without approval. */
final class ReadSqlGuard {

    private ReadSqlGuard() {
    }

    /** True only when both our classifier and the database's own parser (if any) agree the statement is a read. */
    static boolean isRead(DatabaseGateway gateway, String connectionId, String sql) {
        return SqlClassifier.classify(sql) == SqlKind.READ
                && gateway.classifyNative(connectionId, sql).orElse(SqlKind.READ) == SqlKind.READ;
    }
}
