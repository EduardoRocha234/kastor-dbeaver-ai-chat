package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.List;
import java.util.Optional;

/** Database access as seen by the tools. Implemented on top of the DBeaver API in the bundle. */
public interface DatabaseGateway {

    List<ConnectionInfo> listConnections();

    /** Schema paths ("schema" or "catalog.schema"); empty when the database has no schemas. */
    List<String> listSchemas(String connectionId) throws GatewayException;

    /** At most {@code limit} entries. schema null = default schema; nameFilter null = all. */
    List<TableInfo> listTables(String connectionId, String schema, String nameFilter, int limit) throws GatewayException;

    TableDescription describeTable(String connectionId, String schema, String table) throws GatewayException;

    /** Runs an already-classified read statement, returning at most maxRows rows. */
    QueryResult runReadQuery(String connectionId, String sql, int maxRows, CancellationToken cancel) throws GatewayException;

    /** Runs an approved write statement and commits; returns affected rows (-1 if unknown). */
    long executeUpdate(String connectionId, String sql, CancellationToken cancel) throws GatewayException;

    /** Second opinion from the database's own SQL parser, when available. */
    Optional<SqlKind> classifyNative(String connectionId, String sql);
}
