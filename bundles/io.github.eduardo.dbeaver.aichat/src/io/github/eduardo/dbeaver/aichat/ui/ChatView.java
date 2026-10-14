package io.github.eduardo.dbeaver.aichat.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.Activator;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.agent.ChatSession;
import io.github.eduardo.dbeaver.aichat.core.agent.Conversation;
import io.github.eduardo.dbeaver.aichat.core.agent.PromptContext;
import io.github.eduardo.dbeaver.aichat.core.agent.SessionListener;
import io.github.eduardo.dbeaver.aichat.core.agent.SystemPromptBuilder;
import io.github.eduardo.dbeaver.aichat.core.history.ConversationStore;
import io.github.eduardo.dbeaver.aichat.core.history.ConversationSummary;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.ModelLister;
import io.github.eduardo.dbeaver.aichat.core.update.UpdateChecker;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderFactory;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.mention.Mention;
import io.github.eduardo.dbeaver.aichat.core.mention.Mentions;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
import io.github.eduardo.dbeaver.aichat.core.tools.ExportSettings;
import io.github.eduardo.dbeaver.aichat.core.tools.GeneratedFile;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolContext;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolRegistry;
import io.github.eduardo.dbeaver.aichat.dbeaver.DBeaverCompat;
import io.github.eduardo.dbeaver.aichat.dbeaver.DBeaverGateway;
import io.github.eduardo.dbeaver.aichat.dbeaver.EditorContext;
import io.github.eduardo.dbeaver.aichat.dbeaver.EditorContextResolver;
import io.github.eduardo.dbeaver.aichat.settings.AiChatPreferencePage;
import io.github.eduardo.dbeaver.aichat.settings.ProviderSettings;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.Log;
import org.osgi.service.prefs.BackingStoreException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.osgi.framework.Version;

public class ChatView extends ViewPart {

    public static final String ID = "io.github.eduardo.dbeaver.aichat.view";

    private static final Log log = Log.getLog(ChatView.class);
    private static final String EDITOR_CONNECTION = "(conexão do editor ativo)";
    private static final int MAX_INPUT_CHARS = 50_000;
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_TOOL_RESULT_CHARS = 4000;

    private final DBeaverGateway gateway = new DBeaverGateway();
    private final ToolRegistry tools = ToolRegistry.standard(gateway);
    private final HttpClient http = ProviderFactory.defaultHttpClient();
    private final AtomicInteger domCounter = new AtomicInteger();

    private ConversationStore store;
    private Conversation conversation = Conversation.create();
    private List<ProviderConfig> providers = List.of();
    /** Models served by each provider (key = provider id + base URL), fetched once in the background. */
    private final Map<String, List<String>> modelCache = new ConcurrentHashMap<>();
    private final Set<String> modelFetches = ConcurrentHashMap.newKeySet();
    private List<ConnectionInfo> connections = List.of();
    private CancellationToken running;
    /** Connection picked in the header; null = the active editor's connection. */
    private String pickedConnectionId;
    private boolean includeContext = true;

    private Display display;
    private ChatBrowserBridge bridge;
    private UiApprovalGate approvalGate;
    private MentionAssist mentions;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        store = new ConversationStore(Activator.getDefault().conversationsDir());
        parent.setLayout(new FillLayout());
        bridge = new ChatBrowserBridge(parent, new Callbacks());
        approvalGate = new UiApprovalGate(display, bridge);
        bridge.setTheme(isDark(parent));
        bridge.setVersion(versionLabel());
        checkForUpdate();
        mentions = new MentionAssist(display, gateway,
                () -> selectedConnectionId(EditorContextResolver.fromActiveEditor(window())), bridge::showMentions);
        refreshProviders();
        refreshConnections();
        refreshEditorContext();
        showConversationInfo();
    }

    @Override
    public void setFocus() {
        bridge.focusInput();
        refreshProviders();
        refreshConnections();
        refreshEditorContext();
    }

    @Override
    public void dispose() {
        if (running != null) {
            running.cancel();
        }
        if (approvalGate != null) {
            approvalGate.rejectAll();
        }
        gateway.close();
        super.dispose();
    }

    /** Called by "Referenciar no Kastor": selects the objects' connection and adds "@path" mentions to the input. */
    public void reference(String connectionId, List<ReferenceInKastorHandler.Reference> references) {
        selectConnection(connectionId);
        refreshEditorContext();
        List<String> paths = new ArrayList<>();
        for (ReferenceInKastorHandler.Reference r : references) {
            paths.add(r.path());
            if (r.schemaPath() != null) {
                mentions.rememberSchema(connectionId, r.schemaPath());
            }
        }
        bridge.insertMentions(paths);
        bridge.focusInput();
    }

    /** Called by "Perguntar ao Kastor": use the active editor's connection and selection. */
    public void focusWithEditorContext() {
        includeContext = true;
        pickedConnectionId = null;
        refreshConnections();
        refreshEditorContext();
        bridge.setInput("Explique o SQL selecionado.", true);
        bridge.focusInput();
    }

    /** At most once a day, in the background; failures are silent. Skips versions the user dismissed. */
    private void checkForUpdate() {
        long now = System.currentTimeMillis();
        if (!ProviderSettings.updateCheckEnabled() || now - ProviderSettings.lastUpdateCheck() < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        Version v = Activator.getDefault().getBundle().getVersion();
        String current = v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
        Job.create("Kastor: verificar atualização", monitor -> {
            Optional<UpdateChecker.UpdateInfo> update =
                    UpdateChecker.check(current, http, UpdateChecker.LATEST_RELEASE_URL);
            ProviderSettings.saveUpdateState(now, null);
            if (update.isPresent() && !update.get().version().equals(ProviderSettings.dismissedVersion())) {
                display.asyncExec(() -> {
                    if (!bridge.isDisposed()) {
                        bridge.setUpdate(update.get().version(), update.get().url());
                    }
                });
            }
        }).schedule();
    }

    /** "Kastor 0.1.0 · build 13/10/2026 20:57" from the bundle version (qualifier = build timestamp, UTC). */
    private static String versionLabel() {
        Version v = Activator.getDefault().getBundle().getVersion();
        String label = "Kastor " + v.getMajor() + "." + v.getMinor() + "." + v.getMicro();
        String q = v.getQualifier();
        if (q != null && q.matches("\\d{12}")) {
            return label + " · build " + q.substring(6, 8) + "/" + q.substring(4, 6) + "/" + q.substring(0, 4) + " "
                    + q.substring(8, 10) + ":" + q.substring(10, 12) + " UTC";
        }
        return q == null || q.isEmpty() ? label : label + " · " + q;
    }

    /** The page can't see DBeaver's theme (WebView2 follows Windows), so derive it from the SWT background. */
    private static boolean isDark(Composite parent) {
        RGB bg = parent.getBackground().getRGB();
        return 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue < 128;
    }

    private IWorkbenchWindow window() {
        return getSite().getWorkbenchWindow();
    }

    /** Picker entries are "providerId|model": every model a provider serves, or just the configured one until fetched. */
    private void refreshProviders() {
        providers = ProviderSettings.loadProviders();
        ProviderConfig selected = selectedProvider();
        JsonArray entries = new JsonArray();
        if (providers.isEmpty()) {
            entries.add(entry("", "(configure um provedor)"));
        }
        for (ProviderConfig p : providers) {
            fetchModels(p);
            List<String> models = new ArrayList<>(modelCache.getOrDefault(modelKey(p), List.of()));
            if (!models.contains(p.model())) {
                models.add(0, p.model());
            }
            for (String m : models) {
                entries.add(entry(p.id() + "|" + m, p.name() + " — " + m));
            }
        }
        bridge.setProviders(entries, selected == null ? "" : selected.id() + "|" + selected.model());
    }

    private static String modelKey(ProviderConfig p) {
        return p.id() + "|" + p.baseUrl();
    }

    private void fetchModels(ProviderConfig p) {
        String key = modelKey(p);
        if (modelCache.containsKey(key) || !modelFetches.add(key)) {
            return;
        }
        String apiKey = ProviderSettings.apiKey(p.id());
        Job.create("Kastor: listar modelos", monitor -> {
            try {
                modelCache.put(key, ModelLister.list(p, apiKey, http));
                display.asyncExec(() -> {
                    if (!bridge.isDisposed()) {
                        refreshProviders();
                    }
                });
            } catch (LlmException e) {
                log.warn("Could not list models of " + p.name() + ": " + e.getMessage());
            }
        }).schedule();
    }

    /** The picked model becomes the provider's configured model. */
    private void useModel(String providerId, String model) {
        List<ProviderConfig> all = ProviderSettings.loadProviders();
        for (int i = 0; i < all.size(); i++) {
            ProviderConfig p = all.get(i);
            if (p.id().equals(providerId) && !p.model().equals(model)) {
                all.set(i, new ProviderConfig(p.id(), p.name(), p.type(), p.baseUrl(), model, p.maxTokens(),
                        p.temperature(), p.serverFallback()));
                try {
                    ProviderSettings.saveProviders(all);
                } catch (BackingStoreException e) {
                    log.error("Could not save selected model", e);
                }
                refreshProviders();
                return;
            }
        }
    }

    private ProviderConfig selectedProvider() {
        String active = ProviderSettings.activeProviderId();
        return providers.stream().filter(p -> p.id().equals(active)).findFirst()
                .orElse(providers.isEmpty() ? null : providers.get(0));
    }

    private void refreshConnections() {
        connections = gateway.listConnections();
        if (pickedConnectionId != null && connections.stream().noneMatch(c -> c.id().equals(pickedConnectionId))) {
            pickedConnectionId = null;
        }
        JsonArray entries = new JsonArray();
        entries.add(entry("", EDITOR_CONNECTION));
        for (ConnectionInfo c : connections) {
            entries.add(entry(c.id(), c.name() + "  [" + c.project() + "]"));
        }
        bridge.setConnections(entries, pickedConnectionId == null ? "" : pickedConnectionId);
    }

    private void selectConnection(String connectionId) {
        pickedConnectionId = connectionId;
        refreshConnections();
    }

    private String selectedConnectionId(EditorContext active) {
        if (pickedConnectionId != null) {
            return pickedConnectionId;
        }
        return active != null && active.container() != null ? active.container().getId() : null;
    }

    private void refreshEditorContext() {
        EditorContext ctx = EditorContextResolver.fromActiveEditor(window());
        String text;
        if (ctx == null) {
            text = "nenhum editor SQL ativo";
        } else {
            StringBuilder sb = new StringBuilder(ctx.container() == null ? "editor sem conexão" : ctx.container().getName());
            if (ctx.schema() != null) {
                sb.append(" / ").append(ctx.schema());
            }
            if (ctx.selectedText() != null) {
                sb.append(" · ").append(ctx.selectedText().split("\\R", -1).length).append(" linha(s) selecionada(s)");
            }
            text = sb.toString();
        }
        bridge.setEditorContext(text, includeContext);
    }

    private void showConversationInfo() {
        bridge.setTitle(conversation.title());
    }

    private static JsonObject entry(String id, String label) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("label", label);
        return o;
    }

    private void send(String typed) {
        if (running != null) {
            return;
        }
        String text = typed.trim();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_INPUT_CHARS) {
            bridge.addError("Mensagem muito longa (" + text.length() + " caracteres). O limite é de "
                    + MAX_INPUT_CHARS + ".");
            return;
        }
        ProviderConfig config = selectedProvider();
        if (config == null) {
            MessageDialog.openInformation(getSite().getShell(), "Kastor", "Configure um provedor de IA primeiro.");
            openPreferences();
            return;
        }
        refreshConnections();
        EditorContext active = EditorContextResolver.fromActiveEditor(window());
        String connectionId = selectedConnectionId(active);
        ConnectionInfo connection = connections.stream().filter(c -> c.id().equals(connectionId)).findFirst().orElse(null);
        boolean useEditor = includeContext && active != null && active.container() != null
                && active.container().getId().equals(connectionId);
        String dialect = connectionId == null ? null : gateway.dialectName(connectionId);
        String editorSchema = useEditor ? active.schema() : null;
        String editorSql = useEditor ? active.selectedText() : null;
        int maxRows = ProviderSettings.toolLimits().maxRows();
        List<String> knownSchemas = mentions.cachedSchemas(connectionId);

        Conversation current = conversation;
        current.setProviderId(config.id());
        current.setConnectionId(connectionId);
        bridge.addUser(text);
        bridge.clearInput();

        CancellationToken token = new CancellationToken();
        setRunning(token);
        ChatSession session = new ChatSession(current,
                ProviderFactory.create(config, ProviderSettings.apiKey(config.id()), http),
                config, tools, ProviderSettings.sessionSettings());
        UiListener listener = new UiListener(token, current);
        ToolContext toolContext = new ToolContext(connectionId, approvalGate, token, ProviderSettings.toolLimits(),
                ProviderSettings.toolPolicy(), new ExportSettings(ProviderSettings.exportDirectory(),
                        ProviderSettings.exportMaxRows(), listener::fileGenerated));
        Job job = Job.create("Kastor", monitor -> {
            String systemPrompt = SystemPromptBuilder.build(new PromptContext(connection, dialect, editorSchema,
                    editorSql, maxRows, referencedObjects(text, knownSchemas)));
            session.runTurn(text, systemPrompt, toolContext, listener);
            save(current);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    /**
     * Resolves @mentions against the schemas browsed with the @ menu. Never loads all schemas here: on PostgreSQL
     * that connects to every database; unresolved paths go to the model as typed.
     */
    private static List<Mention> referencedObjects(String text, List<String> knownSchemas) {
        return text.contains("@") ? Mentions.parse(text, knownSchemas) : List.of();
    }

    private void setRunning(CancellationToken token) {
        running = token;
        bridge.setRunning(token != null);
    }

    private void save(Conversation c) {
        try {
            store.save(c);
        } catch (IOException | RuntimeException e) {
            log.error("Could not save Kastor conversation", e);
        }
    }

    private boolean busy() {
        if (running != null) {
            MessageDialog.openInformation(getSite().getShell(), "Kastor", "Aguarde ou pare a resposta atual.");
            return true;
        }
        return false;
    }

    private void newConversation() {
        if (busy()) {
            return;
        }
        conversation = Conversation.create();
        bridge.clear();
        showConversationInfo();
        bridge.focusInput();
    }

    private void listConversations() {
        JsonArray entries = new JsonArray();
        try {
            for (ConversationSummary s : store.list()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", s.id());
                o.addProperty("title", s.title());
                o.addProperty("updatedAt", s.updatedAt());
                entries.add(o);
            }
        } catch (IOException e) {
            bridge.addError("Erro ao ler histórico: " + e.getMessage());
        }
        bridge.setConversations(entries, conversation.id());
    }

    private void openConversation(String id) {
        if (busy()) {
            return;
        }
        try {
            conversation = store.load(id);
        } catch (IOException | RuntimeException e) {
            bridge.addError("Erro ao abrir conversa: " + e.getMessage());
            return;
        }
        selectConnection(conversation.connectionId());
        render(conversation);
        showConversationInfo();
    }

    private void render(Conversation c) {
        bridge.clear();
        Map<String, String> toolDomIds = new HashMap<>();
        for (ChatMessage m : c.messages()) {
            switch (m.role()) {
                case USER:
                    bridge.addUser(m.text());
                    break;
                case ASSISTANT: {
                    if (!m.text().isBlank()) {
                        String id = "m" + domCounter.incrementAndGet();
                        bridge.startAssistant(id);
                        bridge.setAssistantMarkdown(id, m.text());
                    }
                    for (ToolCall call : m.toolCalls()) {
                        String domId = "t" + domCounter.incrementAndGet();
                        toolDomIds.put(call.id(), domId);
                        bridge.addTool(domId, call.name(), call.argumentsJson());
                    }
                    break;
                }
                case TOOL: {
                    String domId = toolDomIds.remove(m.toolCallId());
                    if (domId != null) {
                        bridge.setToolResult(domId, abbreviate(m.text()), m.error());
                    }
                    break;
                }
                default:
            }
        }
    }

    private void deleteConversation(String id) {
        boolean current = id.equals(conversation.id());
        if (current && busy()) {
            return;
        }
        try {
            store.delete(id);
        } catch (IOException e) {
            log.error("Could not delete conversation", e);
        }
        if (current) {
            conversation = Conversation.create();
            bridge.clear();
            showConversationInfo();
        }
        listConversations();
    }

    private void openPreferences() {
        PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiChatPreferencePage.ID, null, null).open();
        refreshProviders();
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    private static String abbreviate(String text) {
        return text.length() > MAX_TOOL_RESULT_CHARS ? text.substring(0, MAX_TOOL_RESULT_CHARS) + "\n…" : text;
    }

    private static String size(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes + 512) / 1024 + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void ui(Runnable r) {
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (!bridge.isDisposed()) {
                r.run();
            }
        });
    }

    private final class Callbacks implements ChatBrowserBridge.Callbacks {

        @Override
        public void approve(String id) {
            approvalGate.complete(id, Decision.APPROVED);
        }

        @Override
        public void reject(String id) {
            approvalGate.complete(id, Decision.REJECTED);
        }

        @Override
        public void insertSql(String sql) {
            if (!DBeaverCompat.insertIntoActiveEditor(window(), sql)) {
                openSql(sql);
            }
        }

        @Override
        public void openSql(String sql) {
            DBPDataSourceContainer container =
                    gateway.findContainer(selectedConnectionId(EditorContextResolver.fromActiveEditor(window())));
            if (!DBeaverCompat.openInNewEditor(window(), container, sql)) {
                copyToClipboard(sql);
                MessageDialog.openInformation(getSite().getShell(), "Kastor",
                        "Não foi possível abrir um editor SQL. O SQL foi copiado para a área de transferência.");
            }
        }

        @Override
        public void copy(String text) {
            copyToClipboard(text);
        }

        @Override
        public void send(String text) {
            ChatView.this.send(text);
        }

        @Override
        public void stop() {
            if (running != null) {
                running.cancel();
            }
        }

        @Override
        public void mention(int seq, String query) {
            mentions.query(seq, query);
        }

        @Override
        public void selectProvider(String id) {
            if (id.isEmpty()) {
                openPreferences();
            } else {
                int sep = id.indexOf('|');
                String providerId = sep < 0 ? id : id.substring(0, sep);
                String model = sep < 0 ? null : id.substring(sep + 1);
                ProviderSettings.setActiveProviderId(providerId);
                if (model != null) {
                    useModel(providerId, model);
                }
            }
        }

        @Override
        public void selectConnection(String id) {
            ChatView.this.selectConnection(id.isEmpty() ? null : id);
            refreshEditorContext();
        }

        @Override
        public void toggleContext(boolean include) {
            includeContext = include;
        }

        @Override
        public void newConversation() {
            ChatView.this.newConversation();
        }

        @Override
        public void listConversations() {
            ChatView.this.listConversations();
        }

        @Override
        public void openConversation(String id) {
            ChatView.this.openConversation(id);
        }

        @Override
        public void deleteConversation(String id) {
            ChatView.this.deleteConversation(id);
        }

        @Override
        public void openSettings() {
            openPreferences();
        }

        @Override
        public void dismissUpdate(String version) {
            ProviderSettings.saveUpdateState(ProviderSettings.lastUpdateCheck(), version);
            bridge.hideUpdate();
        }

        @Override
        public void openFile(String path) {
            Path file = exportedFile(path);
            if (file != null && !Program.launch(file.toString())) {
                MessageDialog.openInformation(getSite().getShell(), "Kastor",
                        "Nenhum programa associado a este tipo de arquivo. Ele está em:\n" + file);
            }
        }

        @Override
        public void revealFile(String path) {
            Path file = exportedFile(path);
            if (file != null) {
                Program.launch(file.getParent().toString());
            }
        }

        @Override
        public void saveFileAs(String path) {
            Path file = exportedFile(path);
            if (file == null) {
                return;
            }
            String name = file.getFileName().toString();
            FileDialog dialog = new FileDialog(getSite().getShell(), SWT.SAVE);
            dialog.setFileName(name);
            dialog.setOverwrite(true);
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                dialog.setFilterExtensions(new String[] {"*" + name.substring(dot), "*.*"});
            }
            String target = dialog.open();
            if (target == null) {
                return;
            }
            try {
                Files.copy(file, Paths.get(target), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                MessageDialog.openError(getSite().getShell(), "Kastor", "Não foi possível salvar: " + e.getMessage());
            }
        }

        /** The page is not trusted: only existing files inside the export folder are accepted. */
        private Path exportedFile(String path) {
            try {
                Path dir = ProviderSettings.exportDirectory().toAbsolutePath().normalize();
                Path file = Paths.get(path).toAbsolutePath().normalize();
                if (file.startsWith(dir) && Files.isRegularFile(file)) {
                    return file;
                }
            } catch (RuntimeException e) {
                // invalid path: same message as a missing file
            }
            MessageDialog.openInformation(getSite().getShell(), "Kastor",
                    "O arquivo não está mais na pasta de exportação (foi movido, apagado ou a pasta mudou).");
            return null;
        }
    }

    /** Receives session events on the worker thread and forwards them to the UI thread. */
    private final class UiListener implements SessionListener {

        private final CancellationToken token;
        private final Conversation owner;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> toolDomIds = new HashMap<>();
        private String messageId;
        private boolean flushScheduled;

        UiListener(CancellationToken token, Conversation owner) {
            this.token = token;
            this.owner = owner;
        }

        @Override
        public void onAssistantStart() {
            String id = "m" + domCounter.incrementAndGet();
            synchronized (this) {
                messageId = id;
                text.setLength(0);
            }
            String title = owner.title();
            ui(() -> {
                bridge.startAssistant(id);
                if (owner == conversation) {
                    bridge.setTitle(title);
                }
            });
        }

        @Override
        public void onTextDelta(String delta) {
            synchronized (this) {
                text.append(delta);
                if (flushScheduled) {
                    return;
                }
                flushScheduled = true;
            }
            ui(() -> display.timerExec(50, this::flush));
        }

        private void flush() {
            String id;
            String markdown;
            synchronized (this) {
                flushScheduled = false;
                id = messageId;
                markdown = text.toString();
            }
            if (!bridge.isDisposed()) {
                bridge.setAssistantMarkdown(id, markdown);
            }
        }

        @Override
        public void onAssistantEnd(AssistantTurn turn) {
            String id;
            synchronized (this) {
                id = messageId;
            }
            String finalText = turn.text();
            ui(() -> {
                bridge.setAssistantMarkdown(id, finalText);
                bridge.finishAssistant(id);
            });
        }

        @Override
        public void onToolStart(ToolCall call) {
            String domId = "t" + domCounter.incrementAndGet();
            synchronized (this) {
                toolDomIds.put(call.id(), domId);
            }
            ui(() -> bridge.addTool(domId, call.name(), call.argumentsJson()));
        }

        @Override
        public void onToolEnd(ToolCall call, ToolOutcome outcome) {
            String domId;
            synchronized (this) {
                domId = toolDomIds.remove(call.id());
            }
            if (domId == null) {
                return;
            }
            String content = abbreviate(outcome.content());
            ui(() -> bridge.setToolResult(domId, content, outcome.error()));
        }

        @Override
        public void onIterationLimit(int max) {
            ui(() -> {
                dropPendingBubble();
                bridge.addLimitNotice("Limite de " + max + " chamadas ao modelo atingido nesta pergunta.");
            });
        }

        /** Called by generate_file on the worker thread. */
        void fileGenerated(GeneratedFile file) {
            String details = file.format().toUpperCase(Locale.ROOT) + " · " + size(file.sizeBytes()) + " · "
                    + file.rows() + (file.rows() == 1 ? " linha" : " linhas");
            ui(() -> bridge.addFileCard(file.path().toString(), file.path().getFileName().toString(), file.format(),
                    details));
        }

        @Override
        public void onNotice(String message) {
            ui(() -> {
                dropPendingBubble();
                bridge.addNotice(message);
            });
        }

        @Override
        public void onError(String message) {
            ui(() -> {
                dropPendingBubble();
                bridge.addError(message);
            });
        }

        /** A failed request never reaches onAssistantEnd, which would leave the "typing" bubble spinning. */
        private void dropPendingBubble() {
            String id;
            synchronized (this) {
                id = messageId;
            }
            if (id != null) {
                bridge.finishAssistant(id);
            }
        }

        @Override
        public void onTurnFinished() {
            ui(() -> {
                if (running == token) {
                    setRunning(null);
                }
            });
        }
    }
}
