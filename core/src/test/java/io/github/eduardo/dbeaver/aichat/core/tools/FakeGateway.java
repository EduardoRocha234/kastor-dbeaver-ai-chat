package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

final class FakeGateway implements DatabaseGateway {

    final List<ConnectionInfo> connections = new ArrayList<>(List.of(
            new ConnectionInfo("pg-1", "prod-pg", "PostgreSQL", "General", true, false),
            new ConnectionInfo("lite-1", "local-sqlite", "SQLite", "General", false, true)));
    List<String> schemas = List.of("public", "sales");
    List<TableInfo> tables = List.of(new TableInfo("customers", "TABLE"), new TableInfo("orders", "TABLE"),
            new TableInfo("v_totals", "VIEW"));
    TableDescription description = new TableDescription("public", "orders", "TABLE",
            List.of(new ColumnInfo("id", "integer", false, null),
                    new ColumnInfo("customer_id", "integer", true, null),
                    new ColumnInfo("created_at", "timestamp", false, "now()")),
            List.of("id"),
            List.of(new ForeignKeyInfo("fk_customer", List.of("customer_id"), "public.customers", List.of("id"))),
            List.of(new IndexInfo("idx_orders_customer", false, List.of("customer_id"))));
    QueryResult queryResult = new QueryResult(List.of("id", "name"),
            List.of(List.of("1", "Ana"), Arrays.asList("2", null)), false);
    Optional<SqlKind> nativeKind = Optional.empty();
    GatewayException failure;
    long updateCount = 3;

    final List<String> reads = new ArrayList<>();
    final List<String> writes = new ArrayList<>();
    String lastConnectionId;
    int lastMaxRows;
    int lastLimit;

    @Override
    public List<ConnectionInfo> listConnections() {
        return connections;
    }

    @Override
    public List<String> listSchemas(String connectionId) throws GatewayException {
        lastConnectionId = connectionId;
        if (failure != null) {
            throw failure;
        }
        return schemas;
    }

    @Override
    public List<TableInfo> listTables(String connectionId, String schema, String nameFilter, int limit) {
        lastConnectionId = connectionId;
        lastLimit = limit;
        return tables.subList(0, Math.min(limit, tables.size()));
    }

    @Override
    public TableDescription describeTable(String connectionId, String schema, String table) {
        lastConnectionId = connectionId;
        return description;
    }

    @Override
    public QueryResult runReadQuery(String connectionId, String sql, int maxRows, CancellationToken cancel) {
        lastConnectionId = connectionId;
        lastMaxRows = maxRows;
        reads.add(sql);
        return queryResult;
    }

    @Override
    public long executeUpdate(String connectionId, String sql, CancellationToken cancel) {
        lastConnectionId = connectionId;
        writes.add(sql);
        return updateCount;
    }

    @Override
    public Optional<SqlKind> classifyNative(String connectionId, String sql) {
        return nativeKind;
    }
}
