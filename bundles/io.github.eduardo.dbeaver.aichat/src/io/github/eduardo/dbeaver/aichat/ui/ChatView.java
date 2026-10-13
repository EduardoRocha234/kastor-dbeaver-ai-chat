package io.github.eduardo.dbeaver.aichat.ui;

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
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderFactory;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

public class ChatView extends ViewPart {

    public static final String ID = "io.github.eduardo.dbeaver.aichat.view";

    private static final Log log = Log.getLog(ChatView.class);
    private static final String EDITOR_CONNECTION = "(conexão do editor ativo)";
    private static final int MAX_TOOL_RESULT_CHARS = 4000;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final DBeaverGateway gateway = new DBeaverGateway();
    private final ToolRegistry tools = ToolRegistry.standard(gateway);
    private final HttpClient http = ProviderFactory.defaultHttpClient();
    private final AtomicInteger domCounter = new AtomicInteger();

    private ConversationStore store;
    private Conversation conversation = Conversation.create();
    private List<ProviderConfig> providers = List.of();
    private List<ConnectionInfo> connections = List.of();
    private CancellationToken running;

    private Display display;
    private Combo providerCombo;
    private Combo connectionCombo;
    private Button includeContextCheck;
    private Label contextLabel;
    private ChatBrowserBridge bridge;
    private UiApprovalGate approvalGate;
    private Text input;
    private Button sendButton;
    private Button stopButton;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        store = new ConversationStore(Activator.getDefault().conversationsDir());
        parent.setLayout(new GridLayout(1, false));
        createTopBar(parent);
        createContextBar(parent);
        bridge = new ChatBrowserBridge(parent, new Callbacks());
        bridge.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        approvalGate = new UiApprovalGate(display, bridge);
        createInputArea(parent);
        createActions();
        refreshProviders();
        refreshConnections();
        refreshEditorContext();
    }

    @Override
    public void setFocus() {
        input.setFocus();
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

    /** Called by "Perguntar à IA": use the active editor's connection and selection. */
    public void focusWithEditorContext() {
        includeContextCheck.setSelection(true);
        connectionCombo.select(0);
        refreshEditorContext();
        if (input.getText().isBlank()) {
            input.setText("Explique o SQL selecionado.");
        }
        input.setFocus();
        input.selectAll();
    }

    private void createTopBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(4, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(bar, SWT.NONE).setText("Modelo:");
        providerCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        providerCombo.addListener(SWT.Selection, e -> {
            int i = providerCombo.getSelectionIndex();
            if (i >= 0 && i < providers.size()) {
                ProviderSettings.setActiveProviderId(providers.get(i).id());
            }
        });
        new Label(bar, SWT.NONE).setText("Conexão:");
        connectionCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        connectionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        connectionCombo.addListener(SWT.Selection, e -> refreshEditorContext());
    }

    private void createContextBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(2, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        includeContextCheck = new Button(bar, SWT.CHECK);
        includeContextCheck.setText("Usar contexto do editor:");
        includeContextCheck.setSelection(true);
        contextLabel = new Label(bar, SWT.NONE);
        contextLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createInputArea(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(2, false));
        area.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        input = new Text(area, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputData.heightHint = input.getLineHeight() * 4;
        input.setLayoutData(inputData);
        input.setToolTipText("Ctrl+Enter envia");
        input.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)) {
                e.doit = false;
                send();
            }
        });
        Composite buttons = new Composite(area, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        sendButton = new Button(buttons, SWT.PUSH);
        sendButton.setText("Enviar");
        sendButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sendButton.addListener(SWT.Selection, e -> send());
        stopButton = new Button(buttons, SWT.PUSH);
        stopButton.setText("Parar");
        stopButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, e -> {
            if (running != null) {
                running.cancel();
            }
        });
    }

    private void createActions() {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        toolbar.add(action("Nova conversa", images, ISharedImages.IMG_TOOL_NEW_WIZARD, this::newConversation));
        toolbar.add(action("Histórico de conversas", images, ISharedImages.IMG_OBJ_FOLDER, this::openHistory));
        toolbar.add(action("Apagar conversa atual", images, ISharedImages.IMG_TOOL_DELETE, this::deleteConversation));
        toolbar.add(action("Configurações do AI Chat", images, ISharedImages.IMG_DEF_VIEW, this::openPreferences));
    }

    private static Action action(String text, ISharedImages images, String image, Runnable run) {
        Action action = new Action(text) {
            @Override
            public void run() {
                run.run();
            }
        };
        action.setToolTipText(text);
        action.setImageDescriptor(images.getImageDescriptor(image));
        return action;
    }

    private IWorkbenchWindow window() {
        return getSite().getWorkbenchWindow();
    }

    private void refreshProviders() {
        providers = ProviderSettings.loadProviders();
        String active = ProviderSettings.activeProviderId();
        providerCombo.removeAll();
        if (providers.isEmpty()) {
            providerCombo.add("(configure um provedor)");
            providerCombo.select(0);
            return;
        }
        int selection = 0;
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig p = providers.get(i);
            providerCombo.add(p.name() + " — " + p.model());
            if (p.id().equals(active)) {
                selection = i;
            }
        }
        providerCombo.select(selection);
    }

    private ProviderConfig selectedProvider() {
        int i = providerCombo.getSelectionIndex();
        return i >= 0 && i < providers.size() ? providers.get(i) : null;
    }

    private void refreshConnections() {
        int index = connectionCombo.getSelectionIndex();
        String selectedId = index > 0 && index - 1 < connections.size() ? connections.get(index - 1).id() : null;
        connections = gateway.listConnections();
        connectionCombo.removeAll();
        connectionCombo.add(EDITOR_CONNECTION);
        int selection = 0;
        for (int i = 0; i < connections.size(); i++) {
            ConnectionInfo c = connections.get(i);
            connectionCombo.add(c.name() + "  [" + c.project() + "]");
            if (c.id().equals(selectedId)) {
                selection = i + 1;
            }
        }
        connectionCombo.select(selection);
    }

    private void selectConnection(String connectionId) {
        refreshConnections();
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).id().equals(connectionId)) {
                connectionCombo.select(i + 1);
                return;
            }
        }
        connectionCombo.select(0);
    }

    private String selectedConnectionId(EditorContext active) {
        int index = connectionCombo.getSelectionIndex();
        if (index > 0 && index - 1 < connections.size()) {
            return connections.get(index - 1).id();
        }
        return active != null && active.container() != null ? active.container().getId() : null;
    }

    private void refreshEditorContext() {
        if (contextLabel == null || contextLabel.isDisposed()) {
            return;
        }
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
        contextLabel.setText(text);
        contextLabel.getParent().layout();
    }

    private void send() {
        if (running != null) {
            return;
        }
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        ProviderConfig config = selectedProvider();
        if (config == null) {
            MessageDialog.openInformation(getSite().getShell(), "AI Chat", "Configure um provedor de IA primeiro.");
            openPreferences();
            return;
        }
        refreshConnections();
        EditorContext active = EditorContextResolver.fromActiveEditor(window());
        String connectionId = selectedConnectionId(active);
        ConnectionInfo connection = connections.stream().filter(c -> c.id().equals(connectionId)).findFirst().orElse(null);
        boolean useEditor = includeContextCheck.getSelection() && active != null && active.container() != null
                && active.container().getId().equals(connectionId);
        String systemPrompt = SystemPromptBuilder.build(new PromptContext(connection,
                connectionId == null ? null : gateway.dialectName(connectionId),
                useEditor ? active.schema() : null,
                useEditor ? active.selectedText() : null,
                ProviderSettings.toolLimits().maxRows()));

        Conversation current = conversation;
        current.setProviderId(config.id());
        current.setConnectionId(connectionId);
        bridge.addUser(text);
        input.setText("");

        CancellationToken token = new CancellationToken();
        setRunning(token);
        ChatSession session = new ChatSession(current,
                ProviderFactory.create(config, ProviderSettings.apiKey(config.id()), http),
                config, tools, ProviderSettings.sessionSettings());
        ToolContext toolContext = new ToolContext(connectionId, approvalGate, token, ProviderSettings.toolLimits());
        UiListener listener = new UiListener(token);
        Job job = Job.create("AI Chat", monitor -> {
            session.runTurn(text, systemPrompt, toolContext, listener);
            save(current);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void setRunning(CancellationToken token) {
        running = token;
        if (!sendButton.isDisposed()) {
            sendButton.setEnabled(token == null);
            stopButton.setEnabled(token != null);
        }
    }

    private void save(Conversation c) {
        try {
            store.save(c);
        } catch (IOException | RuntimeException e) {
            log.error("Could not save AI Chat conversation", e);
        }
    }

    private boolean busy() {
        if (running != null) {
            MessageDialog.openInformation(getSite().getShell(), "AI Chat", "Aguarde ou pare a resposta atual.");
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
    }

    private void openHistory() {
        if (busy()) {
            return;
        }
        List<ConversationSummary> list;
        try {
            list = store.list();
        } catch (IOException e) {
            MessageDialog.openError(getSite().getShell(), "AI Chat", "Erro ao ler histórico: " + e.getMessage());
            return;
        }
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override
            public String getText(Object element) {
                ConversationSummary s = (ConversationSummary) element;
                return s.title() + "   (" + DATE_FORMAT.format(Instant.ofEpochMilli(s.updatedAt())) + ")";
            }
        });
        dialog.setTitle("Histórico de conversas");
        dialog.setMessage("Escolha uma conversa:");
        dialog.setElements(list.toArray());
        dialog.setMultipleSelection(false);
        if (dialog.open() == Window.OK && dialog.getFirstResult() instanceof ConversationSummary) {
            openConversation(((ConversationSummary) dialog.getFirstResult()).id());
        }
    }

    private void openConversation(String id) {
        try {
            conversation = store.load(id);
        } catch (IOException | RuntimeException e) {
            MessageDialog.openError(getSite().getShell(), "AI Chat", "Erro ao abrir conversa: " + e.getMessage());
            return;
        }
        selectConnection(conversation.connectionId());
        render(conversation);
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

    private void deleteConversation() {
        if (busy()) {
            return;
        }
        if (!MessageDialog.openConfirm(getSite().getShell(), "AI Chat", "Apagar a conversa atual do histórico?")) {
            return;
        }
        try {
            store.delete(conversation.id());
        } catch (IOException e) {
            log.error("Could not delete conversation", e);
        }
        conversation = Conversation.create();
        bridge.clear();
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
                MessageDialog.openInformation(getSite().getShell(), "AI Chat",
                        "Não foi possível abrir um editor SQL. O SQL foi copiado para a área de transferência.");
            }
        }

        @Override
        public void copy(String text) {
            copyToClipboard(text);
        }
    }

    /** Receives session events on the worker thread and forwards them to the UI thread. */
    private final class UiListener implements SessionListener {

        private final CancellationToken token;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> toolDomIds = new HashMap<>();
        private String messageId;
        private boolean flushScheduled;

        UiListener(CancellationToken token) {
            this.token = token;
        }

        @Override
        public void onAssistantStart() {
            String id = "m" + domCounter.incrementAndGet();
            synchronized (this) {
                messageId = id;
                text.setLength(0);
            }
            ui(() -> bridge.startAssistant(id));
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
        public void onNotice(String message) {
            ui(() -> bridge.addNotice(message));
        }

        @Override
        public void onError(String message) {
            ui(() -> bridge.addError(message));
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
