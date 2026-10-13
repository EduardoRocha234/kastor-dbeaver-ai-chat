package io.github.eduardo.dbeaver.aichat.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.mention.Mentions;
import io.github.eduardo.dbeaver.aichat.core.mention.Suggestion;
import io.github.eduardo.dbeaver.aichat.core.mention.Suggestions;
import io.github.eduardo.dbeaver.aichat.core.tools.ContainerInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.GatewayException;
import io.github.eduardo.dbeaver.aichat.core.tools.TableInfo;
import io.github.eduardo.dbeaver.aichat.dbeaver.DBeaverGateway;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;

/**
 * Answers the chat page's "@" queries: databases of the chat's connection, then a database's schemas, then a
 * schema's tables, narrowed as the user types. Only the level the query reaches is loaded (on PostgreSQL,
 * reading every database's schemas up front opens two connections per database). Loads run in the background
 * and are cached per connection; when one finishes, the latest query is answered again. All state lives on the
 * UI thread.
 */
final class MentionAssist {

    /** Receives (seq, entries) for the page; entries are {path, type, replacement, schema} or {info}. */
    interface Publisher {
        void publish(int seq, JsonArray entries);
    }

    private static final int MAX_PROPOSALS = 200;
    private static final int MAX_TABLES = 5000;

    private final Display display;
    private final DBeaverGateway gateway;
    private final Supplier<String> connection;
    private final Publisher publisher;

    private String connectionId;
    private final Map<String, List<ContainerInfo>> containers = new HashMap<>();
    private final Map<String, List<TableInfo>> tables = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    /** Schema paths learned outside the @ menu ("Referenciar no Kastor"), per connection. */
    private final Map<String, Set<String>> remembered = new HashMap<>();
    private String error;
    private int lastSeq;
    private String lastQuery;

    MentionAssist(Display display, DBeaverGateway gateway, Supplier<String> connection, Publisher publisher) {
        this.display = display;
        this.gateway = gateway;
        this.connection = connection;
        this.publisher = publisher;
    }

    /** Schema paths known for the connection: browsed with the @ menu or remembered. */
    List<String> cachedSchemas(String id) {
        Set<String> out = new LinkedHashSet<>(remembered.getOrDefault(id, Set.of()));
        if (Objects.equals(id, connectionId)) {
            out.addAll(Mentions.schemaPaths(containers));
        }
        return List.copyOf(out);
    }

    void rememberSchema(String id, String schemaPath) {
        remembered.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(schemaPath);
    }

    void query(int seq, String query) {
        lastSeq = seq;
        lastQuery = query;
        publisher.publish(seq, entries(query));
    }

    private JsonArray entries(String query) {
        String id = connection.get();
        if (id == null) {
            return info("Escolha uma conexão no seletor \"Conexão\" (ou abra um editor SQL) para usar @.");
        }
        if (!id.equals(connectionId)) {
            connectionId = id;
            containers.clear();
            tables.clear();
            loading.clear();
            error = null;
        }
        if (error != null) {
            String message = error;
            error = null;   // the next query retries
            return info(message);
        }
        Suggestions s = Mentions.suggest(query, containers, tables, MAX_PROPOSALS);
        if (s.pendingContainers() != null) {
            loadContainers(id, s.pendingContainers());
            return info(s.pendingContainers().isEmpty() ? "Carregando bancos e schemas…"
                    : "Carregando schemas de " + s.pendingContainers() + "…");
        }
        if (s.pendingTables() != null) {
            loadTables(id, s.pendingTables());
            return info("Carregando tabelas" + (s.pendingTables().isEmpty() ? "" : " de " + s.pendingTables()) + "…");
        }
        if (s.items().isEmpty()) {
            return info("Nenhum schema ou tabela corresponde a \"" + query + "\".");
        }
        JsonArray out = new JsonArray();
        for (Suggestion item : s.items()) {
            JsonObject o = new JsonObject();
            o.addProperty("path", item.path());
            o.addProperty("type", item.type().toLowerCase(Locale.ROOT));
            o.addProperty("replacement", item.replacement());
            o.addProperty("drill", item.drill());
            out.add(o);
        }
        return out;
    }

    private void loadContainers(String id, String parent) {
        String key = "containers:" + parent;
        if (loading.add(key)) {
            background(id, key, () -> gateway.listContainers(id, parent.isEmpty() ? null : parent),
                    result -> containers.put(parent, result));
        }
    }

    private void loadTables(String id, String schema) {
        String key = "tables:" + schema;
        if (loading.add(key)) {
            background(id, key, () -> gateway.listTables(id, schema.isEmpty() ? null : schema, null, MAX_TABLES),
                    result -> tables.put(schema, result));
        }
    }

    private interface Load<T> {
        T run() throws GatewayException;
    }

    private <T> void background(String id, String key, Load<T> load, Consumer<T> store) {
        Job job = Job.create("Kastor: carregando objetos", monitor -> {
            T result = null;
            String failure = null;
            try {
                result = load.run();
            } catch (GatewayException | RuntimeException e) {
                failure = "Não foi possível carregar objetos: " + e.getMessage();
            }
            T loaded = result;
            String message = failure;
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!id.equals(connectionId)) {
                        return;
                    }
                    loading.remove(key);
                    if (message != null) {
                        error = message;
                    } else {
                        store.accept(loaded);
                    }
                    if (lastQuery != null) {
                        query(lastSeq, lastQuery);
                    }
                });
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private static JsonArray info(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("info", message);
        JsonArray out = new JsonArray();
        out.add(o);
        return out;
    }
}
