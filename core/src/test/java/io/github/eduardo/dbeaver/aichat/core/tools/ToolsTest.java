package io.github.eduardo.dbeaver.aichat.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolsTest {

    private FakeGateway gateway;
    private ToolRegistry registry;
    private final List<ApprovalRequest> approvals = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        registry = ToolRegistry.standard(gateway);
        approvals.clear();
    }

    private ToolContext ctx(String defaultConnection, Decision decision) {
        ApprovalGate gate = (request, cancel) -> {
            approvals.add(request);
            return decision;
        };
        return new ToolContext(defaultConnection, gate, new CancellationToken(), new ToolLimits(200, 16000, 2));
    }

    private ToolOutcome call(String tool, String args, ToolContext ctx) {
        return registry.execute(new ToolCall("c1", tool, args), ctx);
    }

    @Test
    void exposesSixTools() {
        List<String> names = registry.specs().stream().map(s -> s.name()).toList();
        assertEquals(List.of("list_connections", "list_schemas", "list_tables", "describe_table", "run_query",
                "execute_write"), names);
        registry.specs().forEach(s -> assertEquals("object", s.inputSchema().get("type").getAsString()));
    }

    @Test
    void listConnections() {
        ToolOutcome out = call("list_connections", "{}", ctx(null, Decision.APPROVED));
        assertFalse(out.error());
        assertTrue(out.content().contains("prod-pg (id: pg-1"), out.content());
        assertTrue(out.content().contains("somente leitura"), out.content());
    }

    @Test
    void resolvesConnectionByNameIgnoringCase() {
        ToolOutcome out = call("run_query", "{\"connection\":\"PROD-PG\",\"sql\":\"select 1\"}", ctx(null, Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals("pg-1", gateway.lastConnectionId);
    }

    @Test
    void usesDefaultConnection() {
        call("list_schemas", "{}", ctx("pg-1", Decision.APPROVED));
        assertEquals("pg-1", gateway.lastConnectionId);
    }

    @Test
    void missingConnectionListsAvailableOnes() {
        ToolOutcome out = call("list_schemas", "{}", ctx(null, Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("prod-pg, local-sqlite"), out.content());
    }

    @Test
    void listTablesShowsTruncationNotice() {
        ToolOutcome out = call("list_tables", "{\"schema\":\"public\"}", ctx("pg-1", Decision.APPROVED));
        assertEquals(3, gateway.lastLimit);
        assertTrue(out.content().contains("customers [TABLE]"));
        assertTrue(out.content().contains("orders [TABLE]"));
        assertFalse(out.content().contains("v_totals"));
        assertTrue(out.content().contains("omitidos"));
    }

    @Test
    void describeTableFormatsKeys() {
        ToolOutcome out = call("describe_table", "{\"table\":\"orders\"}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.content().contains("created_at timestamp NOT NULL DEFAULT now()"), out.content());
        assertTrue(out.content().contains("Chave primária: id"));
        assertTrue(out.content().contains("fk_customer (customer_id) -> public.customers (id)"));
        assertTrue(out.content().contains("idx_orders_customer (customer_id)"));
    }

    @Test
    void runQueryRejectsWrites() {
        ToolOutcome out = call("run_query", "{\"sql\":\"delete from orders\"}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("execute_write"));
        assertTrue(gateway.reads.isEmpty());
    }

    @Test
    void runQueryRejectsWhenNativeParserSaysWrite() {
        gateway.nativeKind = Optional.of(SqlKind.WRITE);
        ToolOutcome out = call("run_query", "{\"sql\":\"select f()\"}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(gateway.reads.isEmpty());
    }

    @Test
    void runQueryFormatsRowsAndCapsMaxRows() {
        ToolOutcome out = call("run_query", "{\"sql\":\"select id, name from c\",\"max_rows\":5000}", ctx("pg-1", Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals(200, gateway.lastMaxRows);
        assertTrue(out.content().startsWith("2 linha(s)"), out.content());
        assertTrue(out.content().contains("2 | NULL"), out.content());
    }

    @Test
    void executeWriteRunsAfterApproval() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from orders where id = 3\",\"reason\":\"limpar teste\"}",
                ctx("pg-1", Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals(List.of("delete from orders where id = 3"), gateway.writes);
        assertEquals("prod-pg", approvals.get(0).connectionName());
        assertEquals("limpar teste", approvals.get(0).reason());
        assertTrue(out.content().contains("Linhas afetadas: 3"));
    }

    @Test
    void runQueryPassesSchemaToGateway() {
        ToolOutcome out = call("run_query", "{\"sql\":\"select 1\",\"schema\":\"base_dev.public\"}", ctx("pg-1", Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals("base_dev.public", gateway.lastSchema);
    }

    @Test
    void runQueryWithoutSchemaUsesDefault() {
        call("run_query", "{\"sql\":\"select 1\"}", ctx("pg-1", Decision.APPROVED));
        assertNull(gateway.lastSchema);
    }

    @Test
    void executeWritePassesSchemaAndShowsItInApproval() {
        ToolOutcome out = call("execute_write",
                "{\"sql\":\"delete from t\",\"reason\":\"x\",\"schema\":\"base_dev.public\"}", ctx("pg-1", Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals("base_dev.public", gateway.lastSchema);
        assertEquals("prod-pg / base_dev.public", approvals.get(0).connectionName());
    }

    @Test
    void executeWriteRejectedByUser() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from orders\",\"reason\":\"x\"}", ctx("pg-1", Decision.REJECTED));
        assertTrue(out.error());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    void executeWriteBlockedOnReadOnlyConnection() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from t\",\"reason\":\"x\",\"connection\":\"lite-1\"}",
                ctx(null, Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(approvals.isEmpty());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    void executeWriteRefusesMultipleStatements() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from a; delete from b\",\"reason\":\"x\"}",
                ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(approvals.isEmpty());
    }

    @Test
    void invalidJsonArgumentsAreReported() {
        ToolOutcome out = call("run_query", "{\"sql\": \"sel", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("INVALID_JSON"));
    }

    @Test
    void unknownToolIsReported() {
        ToolOutcome out = call("drop_everything", "{}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("drop_everything"));
    }

    @Test
    void gatewayFailureBecomesErrorOutcome() {
        gateway.failure = new GatewayException("Falha ao conectar em prod-pg: timeout", null);
        ToolOutcome out = call("list_schemas", "{}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("timeout"));
    }

    @Test
    void missingRequiredParameter() {
        ToolOutcome out = call("describe_table", "{}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("table"));
    }
}
