package io.github.eduardo.dbeaver.aichat.dbeaver;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import io.github.eduardo.dbeaver.aichat.core.tools.ColumnInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.DatabaseGateway;
import io.github.eduardo.dbeaver.aichat.core.tools.ForeignKeyInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.GatewayException;
import io.github.eduardo.dbeaver.aichat.core.tools.IndexInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.QueryResult;
import io.github.eduardo.dbeaver.aichat.core.tools.TableDescription;
import io.github.eduardo.dbeaver.aichat.core.tools.TableInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.runtime.DBWorkbench;

/** DatabaseGateway on top of DBeaver's data source registry and execution API. */
public final class DBeaverGateway implements DatabaseGateway {

    private static final Log log = Log.getLog(DBeaverGateway.class);
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final int MAX_CELL_CHARS = 2000;

    /** isolated = auto-commit was disabled on a context owned by this gateway. */
    private record ContextHandle(DBCExecutionContext context, boolean isolated) {
    }

    private final Map<String, ContextHandle> contexts = new ConcurrentHashMap<>();

    @Override
    public List<ConnectionInfo> listConnections() {
        List<ConnectionInfo> out = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            if (registry == null) {
                continue;
            }
            for (DBPDataSourceContainer c : registry.getDataSources()) {
                out.add(new ConnectionInfo(c.getId(), c.getName(),
                        c.getDriver() == null ? "?" : c.getDriver().getName(), project.getName(),
                        c.isConnected(), c.isConnectionReadOnly()));
            }
        }
        return out;
    }

    public DBPDataSourceContainer findContainer(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            DBPDataSourceContainer c = registry == null ? null : registry.getDataSource(connectionId);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** SQL dialect name when connected, otherwise the driver name. Never connects. */
    public String dialectName(String connectionId) {
        DBPDataSourceContainer c = findContainer(connectionId);
        if (c == null) {
            return null;
        }
        DBPDataSource ds = c.getDataSource();
        if (c.isConnected() && ds != null && ds.getSQLDialect() != null) {
            return ds.getSQLDialect().getDialectName();
        }
        return c.getDriver() == null ? null : c.getDriver().getName();
    }

    @Override
    public List<String> listSchemas(String connectionId) throws GatewayException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBSObjectContainer root = root(connect(container(connectionId)));
        List<String> out = new ArrayList<>();
        try {
            for (DBSObject child : children(root, monitor)) {
                if (child instanceof DBSCatalog) {
                    boolean hasSchemas = false;
                    for (DBSObject sub : children((DBSCatalog) child, monitor)) {
                        if (sub instanceof DBSSchema) {
                            out.add(child.getName() + "." + sub.getName());
                            hasSchemas = true;
                        }
                    }
                    if (!hasSchemas) {
                        out.add(child.getName());
                    }
                } else if (child instanceof DBSSchema) {
                    out.add(child.getName());
                }
            }
        } catch (DBException e) {
            throw new GatewayException("Falha ao ler schemas: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<TableInfo> listTables(String connectionId, String schema, String nameFilter, int limit)
            throws GatewayException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBPDataSource ds = connect(container(connectionId));
        String filter = nameFilter == null ? null : nameFilter.toLowerCase(Locale.ROOT);
        List<TableInfo> out = new ArrayList<>();
        try {
            for (DBSObject child : children(resolveContainer(ds, schema, monitor), monitor)) {
                if (!(child instanceof DBSEntity)) {
                    continue;
                }
                if (filter != null && !child.getName().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                out.add(new TableInfo(child.getName(), entityType((DBSEntity) child)));
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (DBException e) {
            throw new GatewayException("Falha ao listar tabelas: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public TableDescription describeTable(String connectionId, String schema, String table) throws GatewayException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBPDataSource ds = connect(container(connectionId));
        try {
            DBSObjectContainer parent = resolveContainer(ds, schema, monitor);
            DBSObject object = child(parent, table, monitor);
            if (!(object instanceof DBSEntity)) {
                throw new GatewayException("Tabela não encontrada: " + table, null);
            }
            DBSEntity entity = (DBSEntity) object;
            List<ColumnInfo> columns = new ArrayList<>();
            Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
            if (attributes != null) {
                for (DBSEntityAttribute a : attributes) {
                    columns.add(new ColumnInfo(a.getName(), a.getFullTypeName(), !a.isRequired(), a.getDefaultValue()));
                }
            }
            List<String> primaryKey = new ArrayList<>();
            Collection<? extends DBSEntityConstraint> constraints = entity.getConstraints(monitor);
            if (constraints != null) {
                for (DBSEntityConstraint c : constraints) {
                    if (c.getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY && c instanceof DBSEntityReferrer) {
                        primaryKey.addAll(attributeNames((DBSEntityReferrer) c, monitor));
                    }
                }
            }
            List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
            Collection<? extends DBSEntityAssociation> associations = entity.getAssociations(monitor);
            if (associations != null) {
                for (DBSEntityAssociation fk : associations) {
                    DBSEntity ref = fk.getAssociatedEntity();
                    DBSEntityConstraint refConstraint = fk.getReferencedConstraint();
                    foreignKeys.add(new ForeignKeyInfo(fk.getName(),
                            fk instanceof DBSEntityReferrer ? attributeNames((DBSEntityReferrer) fk, monitor) : List.of(),
                            ref == null ? "?" : qualifiedName(ref),
                            refConstraint instanceof DBSEntityReferrer
                                    ? attributeNames((DBSEntityReferrer) refConstraint, monitor) : List.of()));
                }
            }
            List<IndexInfo> indexes = new ArrayList<>();
            if (entity instanceof DBSTable) {
                Collection<? extends DBSTableIndex> tableIndexes = ((DBSTable) entity).getIndexes(monitor);
                if (tableIndexes != null) {
                    for (DBSTableIndex idx : tableIndexes) {
                        indexes.add(new IndexInfo(idx.getName(), idx.isUnique(), attributeNames(idx, monitor)));
                    }
                }
            }
            String schemaName = entity.getParentObject() == null ? schema : entity.getParentObject().getName();
            return new TableDescription(schemaName, entity.getName(), entityType(entity), columns, primaryKey,
                    foreignKeys, indexes);
        } catch (DBException e) {
            throw new GatewayException("Falha ao descrever " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public QueryResult runReadQuery(String connectionId, String sql, int maxRows, CancellationToken cancel)
            throws GatewayException {
        ContextHandle handle = context(connectionId);
        if (!handle.isolated()) {
            // Without our own transaction a read can't be rolled back and would run in the
            // user's editor session; the classifier alone is not enough of a barrier.
            throw new GatewayException("Esta conexão não permite abrir uma transação separada para o chat; "
                    + "consultas pelo chat estão bloqueadas nela.", null);
        }
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        try (DBCSession session = handle.context().openSession(monitor, DBCExecutionPurpose.UTIL, "AI Chat query")) {
            DBCTransactionManager txn = DBUtils.getTransactionManager(handle.context());
            DBeaverCompat.setReadOnly(session, true);
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false);
                    CancellationToken.Registration registration = cancel.onCancel(() -> cancelQuietly(statement, monitor))) {
                statement.setLimit(0, maxRows + 1L);
                statement.setStatementTimeout(QUERY_TIMEOUT_SECONDS);
                if (!statement.executeStatement()) {
                    return new QueryResult(List.of("resultado"),
                            List.of(List.of("Comando executado sem conjunto de resultados.")), false);
                }
                try (DBCResultSet rs = statement.openResultSet()) {
                    List<String> columns = new ArrayList<>();
                    for (DBCAttributeMetaData attribute : rs.getMeta().getAttributes()) {
                        columns.add(attribute.getLabel());
                    }
                    List<List<String>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.nextRow()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        List<String> row = new ArrayList<>(columns.size());
                        for (int i = 0; i < columns.size(); i++) {
                            row.add(cell(rs.getAttributeValue(i)));
                        }
                        rows.add(row);
                    }
                    return new QueryResult(columns, rows, truncated);
                }
            } finally {
                if (txn != null) {
                    rollbackQuietly(txn, session);
                }
            }
        } catch (DBException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new GatewayException("Erro ao executar consulta: " + e.getMessage(), e);
        }
    }

    @Override
    public long executeUpdate(String connectionId, String sql, CancellationToken cancel) throws GatewayException {
        ContextHandle handle = context(connectionId);
        if (!handle.isolated()) {
            // Same reason as reads: we can't commit or roll back on the user's session.
            throw new GatewayException("Esta conexão não permite abrir uma transação separada para o chat; "
                    + "comandos pelo chat estão bloqueados nela.", null);
        }
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        try (DBCSession session = handle.context().openSession(monitor, DBCExecutionPurpose.USER, "AI Chat write")) {
            DBCTransactionManager txn = DBUtils.getTransactionManager(handle.context());
            DBeaverCompat.setReadOnly(session, false);
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false);
                    CancellationToken.Registration registration = cancel.onCancel(() -> cancelQuietly(statement, monitor))) {
                statement.setStatementTimeout(QUERY_TIMEOUT_SECONDS);
                statement.executeStatement();
                long count = statement.getUpdateRowCount();
                if (txn != null) {
                    txn.commit(session);
                }
                return count;
            } catch (DBException | RuntimeException e) {
                if (txn != null) {
                    rollbackQuietly(txn, session);
                }
                throw e;
            }
        } catch (DBException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new GatewayException("Erro ao executar comando: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<SqlKind> classifyNative(String connectionId, String sql) {
        try {
            return DBeaverCompat.classifyNative(connect(container(connectionId)), sql);
        } catch (GatewayException e) {
            return Optional.empty();
        }
    }

    /** Closes the isolated contexts opened by this gateway. */
    public void close() {
        for (ContextHandle handle : contexts.values()) {
            if (handle.isolated()) {
                DBeaverCompat.closeQuietly(handle.context());
            }
        }
        contexts.clear();
    }

    private DBPDataSourceContainer container(String connectionId) throws GatewayException {
        DBPDataSourceContainer c = findContainer(connectionId);
        if (c == null) {
            throw new GatewayException("Conexão não encontrada: " + connectionId, null);
        }
        return c;
    }

    private static DBPDataSource connect(DBPDataSourceContainer c) throws GatewayException {
        if (!c.isConnected()) {
            try {
                c.connect(new VoidProgressMonitor(), true, true);
            } catch (DBException e) {
                throw new GatewayException("Falha ao conectar em " + c.getName() + ": " + e.getMessage(), e);
            }
        }
        DBPDataSource ds = c.getDataSource();
        if (ds == null) {
            throw new GatewayException("A conexão " + c.getName() + " não está disponível.", null);
        }
        return ds;
    }

    private synchronized ContextHandle context(String connectionId) throws GatewayException {
        DBPDataSource ds = connect(container(connectionId));
        ContextHandle existing = contexts.get(connectionId);
        if (existing != null && existing.context().getDataSource() == ds && existing.context().isConnected()) {
            return existing;
        }
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBSInstance instance = ds.getDefaultInstance();
        DBCExecutionContext defaultContext = instance.getDefaultContext(monitor, false);
        ContextHandle handle;
        try {
            DBCExecutionContext isolated = instance.openIsolatedContext(monitor, "AI Chat", defaultContext);
            DBCTransactionManager txn = DBUtils.getTransactionManager(isolated);
            if (txn != null && txn.isAutoCommit()) {
                txn.setAutoCommit(monitor, false);
            }
            handle = new ContextHandle(isolated, txn != null);
        } catch (DBException | RuntimeException e) {
            log.debug("Isolated context not available, using default context", e);
            handle = new ContextHandle(defaultContext, false);
        }
        contexts.put(connectionId, handle);
        return handle;
    }

    private static DBSObjectContainer root(DBPDataSource ds) throws GatewayException {
        if (ds instanceof DBSObjectContainer) {
            return (DBSObjectContainer) ds;
        }
        throw new GatewayException("Esta conexão não expõe objetos navegáveis.", null);
    }

    /** schema null -> default schema/catalog of the default context; "a.b" -> catalog a, schema b. */
    private DBSObjectContainer resolveContainer(DBPDataSource ds, String schema, DBRProgressMonitor monitor)
            throws DBException, GatewayException {
        DBSObjectContainer root = root(ds);
        DBCExecutionContext ctx = ds.getDefaultInstance().getDefaultContext(monitor, true);
        var defaults = ctx == null ? null : ctx.getContextDefaults();
        if (schema == null) {
            if (defaults != null && defaults.getDefaultSchema() instanceof DBSObjectContainer) {
                return (DBSObjectContainer) defaults.getDefaultSchema();
            }
            if (defaults != null && defaults.getDefaultCatalog() instanceof DBSObjectContainer) {
                return (DBSObjectContainer) defaults.getDefaultCatalog();
            }
            return root;
        }
        String[] parts = schema.split("\\.", 2);
        DBSObject first = child(root, parts[0], monitor);
        if (first == null && parts.length == 1 && defaults != null
                && defaults.getDefaultCatalog() instanceof DBSObjectContainer) {
            first = child((DBSObjectContainer) defaults.getDefaultCatalog(), parts[0], monitor);
        }
        DBSObject target = first;
        if (parts.length == 2 && first instanceof DBSObjectContainer) {
            target = child((DBSObjectContainer) first, parts[1], monitor);
        }
        if (!(target instanceof DBSObjectContainer)) {
            throw new GatewayException("Schema não encontrado: " + schema + ". Use list_schemas.", null);
        }
        return (DBSObjectContainer) target;
    }

    private static Collection<? extends DBSObject> children(DBSObjectContainer container, DBRProgressMonitor monitor)
            throws DBException {
        Collection<? extends DBSObject> children = container.getChildren(monitor);
        return children == null ? List.of() : children;
    }

    private static DBSObject child(DBSObjectContainer container, String name, DBRProgressMonitor monitor)
            throws DBException {
        DBSObject exact = container.getChild(monitor, name);
        if (exact != null) {
            return exact;
        }
        for (DBSObject o : children(container, monitor)) {
            if (o.getName().equalsIgnoreCase(name)) {
                return o;
            }
        }
        return null;
    }

    private static List<String> attributeNames(DBSEntityReferrer referrer, DBRProgressMonitor monitor)
            throws DBException {
        List<String> names = new ArrayList<>();
        var refs = referrer.getAttributeReferences(monitor);
        if (refs != null) {
            for (DBSEntityAttributeRef ref : refs) {
                if (ref.getAttribute() != null) {
                    names.add(ref.getAttribute().getName());
                }
            }
        }
        return names;
    }

    private static String entityType(DBSEntity entity) {
        if (entity instanceof DBSTable && ((DBSTable) entity).isView()) {
            return "VIEW";
        }
        return entity.getEntityType() == null ? "TABLE" : entity.getEntityType().getName().toUpperCase(Locale.ROOT);
    }

    private static String qualifiedName(DBSEntity entity) {
        DBSObject parent = entity.getParentObject();
        return parent == null || parent instanceof DBPDataSource ? entity.getName() : parent.getName() + "." + entity.getName();
    }

    private static String cell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return "<binário " + ((byte[]) value).length + " bytes>";
        }
        String s = String.valueOf(value);
        return s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) + "…" : s;
    }

    private static void cancelQuietly(DBCStatement statement, DBRProgressMonitor monitor) {
        try {
            statement.cancelBlock(monitor, null);
        } catch (DBException | RuntimeException e) {
            log.debug("Cancel failed", e);
        }
    }

    private static void rollbackQuietly(DBCTransactionManager txn, DBCSession session) {
        try {
            txn.rollback(session, null);
        } catch (DBException | RuntimeException e) {
            log.debug("Rollback failed", e);
        }
    }
}
