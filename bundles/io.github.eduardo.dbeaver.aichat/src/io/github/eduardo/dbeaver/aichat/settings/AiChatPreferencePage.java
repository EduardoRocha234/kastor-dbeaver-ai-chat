package io.github.eduardo.dbeaver.aichat.settings;

import io.github.eduardo.dbeaver.aichat.Activator;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.agent.SessionSettings;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatRequest;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderFactory;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolLimits;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class AiChatPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String ID = "io.github.eduardo.dbeaver.aichat.preferences";

    private final List<ProviderConfig> providers = new ArrayList<>();
    private final Map<String, String> pendingKeys = new HashMap<>();
    private final Set<String> removedIds = new HashSet<>();
    private String activeId;

    private TableViewer viewer;
    private Spinner maxRows;
    private Spinner maxChars;
    private Spinner maxIterations;
    private Button allowWrites;
    private Text maskColumns;
    private Button audit;

    @Override
    public void init(IWorkbench workbench) {
        providers.addAll(ProviderSettings.loadProviders());
        activeId = ProviderSettings.activeProviderId();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));

        Label title = new Label(root, SWT.NONE);
        title.setText("Provedores de IA (★ = padrão):");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        viewer = new TableViewer(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 160;
        table.setLayoutData(tableData);
        column("Nome", 160, p -> (p.id().equals(activeId) ? "★ " : "") + p.name());
        column("Tipo", 120, p -> p.type() == ProviderType.ANTHROPIC ? "Anthropic" : "OpenAI-compatible");
        column("Modelo", 160, ProviderConfig::model);
        column("URL base", 220, ProviderConfig::baseUrl);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setInput(providers);
        viewer.addDoubleClickListener(e -> editProvider());

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        button(buttons, "Adicionar…", this::addProvider);
        button(buttons, "Editar…", this::editProvider);
        button(buttons, "Remover", this::removeProvider);
        button(buttons, "Usar como padrão", this::makeDefault);
        button(buttons, "Testar", this::testProvider);

        Group limits = new Group(root, SWT.NONE);
        limits.setText("Limites");
        limits.setLayout(new GridLayout(2, false));
        limits.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        ToolLimits toolLimits = ProviderSettings.toolLimits();
        maxRows = spinner(limits, "Máximo de linhas por consulta:", 1, 5000, toolLimits.maxRows());
        maxChars = spinner(limits, "Máximo de caracteres por resultado:", 1000, 200_000, toolLimits.maxResultChars());
        maxIterations = spinner(limits, "Máximo de chamadas ao modelo por pergunta:", 1, 50,
                ProviderSettings.sessionSettings().maxIterations());

        createSecurityGroup(root);

        Label warning = new Label(root, SWT.WRAP);
        warning.setText("Atenção: nomes de tabelas, estruturas e resultados de consultas (até os limites acima) "
                + "são enviados ao provedor de IA configurado, exceto os valores de colunas mascaradas. "
                + "Comandos de escrita sempre exigem sua aprovação no chat.");
        GridData warningData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        warningData.widthHint = 520;
        warning.setLayoutData(warningData);
        return root;
    }

    private void createSecurityGroup(Composite root) {
        Group group = new Group(root, SWT.NONE);
        group.setText("Segurança");
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        allowWrites = new Button(group, SWT.CHECK);
        boolean writesLocked = ProviderSettings.writesLockedByAdmin();
        allowWrites.setText(writesLocked ? "Permitir comandos de escrita (desativado pelo administrador)"
                : "Permitir comandos de escrita (sempre com aprovação)");
        allowWrites.setSelection(ProviderSettings.writesAllowed());
        allowWrites.setEnabled(!writesLocked);
        allowWrites.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label maskLabel = new Label(group, SWT.WRAP);
        String adminMask = ProviderSettings.adminMaskedColumns().trim();
        maskLabel.setText("Colunas mascaradas (nomes separados por vírgula; * e ? são curingas; ex.: cpf, *senha*, email)."
                + " Os valores dessas colunas não são enviados ao provedor de IA."
                + (adminMask.isEmpty() ? "" : " Definidas pelo administrador: " + adminMask + "."));
        GridData maskLabelData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        maskLabelData.widthHint = 500;
        maskLabel.setLayoutData(maskLabelData);
        maskColumns = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        maskColumns.setText(ProviderSettings.userMaskedColumns());
        GridData maskData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        maskData.heightHint = 48;
        maskColumns.setLayoutData(maskData);

        audit = new Button(group, SWT.CHECK);
        boolean auditLocked = ProviderSettings.auditLockedByAdmin();
        audit.setText(auditLocked ? "Registrar auditoria local (exigido pelo administrador)"
                : "Registrar auditoria local (consultas e comandos pedidos pelo modelo)");
        audit.setSelection(ProviderSettings.auditEnabled());
        audit.setEnabled(!auditLocked);
        audit.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label path = new Label(group, SWT.WRAP);
        path.setText("Arquivo: " + auditFile());
        path.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button(group, "Abrir pasta", this::openAuditFolder);
    }

    private static java.nio.file.Path auditFile() {
        return Activator.getDefault().auditFile();
    }

    private void openAuditFolder() {
        try {
            java.nio.file.Path dir = auditFile().getParent();
            java.nio.file.Files.createDirectories(dir);
            Program.launch(dir.toString());
        } catch (java.io.IOException e) {
            MessageDialog.openError(getShell(), "Kastor", "Não foi possível abrir a pasta: " + e.getMessage());
        }
    }

    private void column(String title, int width, Function<ProviderConfig, String> text) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return text.apply((ProviderConfig) element);
            }
        });
    }

    private static void button(Composite parent, String text, Runnable action) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        b.addListener(SWT.Selection, e -> action.run());
    }

    private static Spinner spinner(Composite parent, String label, int min, int max, int value) {
        new Label(parent, SWT.NONE).setText(label);
        Spinner s = new Spinner(parent, SWT.BORDER);
        s.setMinimum(min);
        s.setMaximum(max);
        s.setSelection(value);
        return s;
    }

    private ProviderConfig selected() {
        Object o = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
        return o instanceof ProviderConfig ? (ProviderConfig) o : null;
    }

    private void addProvider() {
        ProviderDialog dialog = new ProviderDialog(getShell(), null);
        if (dialog.open() != Window.OK) {
            return;
        }
        ProviderConfig config = dialog.result();
        providers.add(config);
        pendingKeys.put(config.id(), dialog.apiKey());
        if (activeId == null) {
            activeId = config.id();
        }
        viewer.refresh();
    }

    private void editProvider() {
        ProviderConfig current = selected();
        if (current == null) {
            return;
        }
        ProviderDialog dialog = new ProviderDialog(getShell(), current);
        if (dialog.open() != Window.OK) {
            return;
        }
        providers.set(providers.indexOf(current), dialog.result());
        if (dialog.apiKey() != null) {
            pendingKeys.put(current.id(), dialog.apiKey());
        }
        viewer.refresh();
    }

    private void removeProvider() {
        ProviderConfig current = selected();
        if (current == null) {
            return;
        }
        providers.remove(current);
        pendingKeys.remove(current.id());
        removedIds.add(current.id());
        if (current.id().equals(activeId)) {
            activeId = providers.isEmpty() ? null : providers.get(0).id();
        }
        viewer.refresh();
    }

    private void makeDefault() {
        ProviderConfig current = selected();
        if (current != null) {
            activeId = current.id();
            viewer.refresh();
        }
    }

    private void testProvider() {
        ProviderConfig config = selected();
        if (config == null) {
            return;
        }
        String key = pendingKeys.containsKey(config.id()) ? pendingKeys.get(config.id()) : ProviderSettings.apiKey(config.id());
        String[] reply = new String[1];
        try {
            new ProgressMonitorDialog(getShell()).run(true, false, (IProgressMonitor monitor) -> {
                monitor.beginTask("Testando " + config.name() + "…", IProgressMonitor.UNKNOWN);
                try {
                    AssistantTurn turn = ProviderFactory.create(config, key, ProviderFactory.defaultHttpClient()).stream(
                            new ChatRequest(config.model(), "Responda apenas com a palavra OK.",
                                    List.of(ChatMessage.user("Teste de conexão")), List.of(), 1024, null),
                            t -> { }, new CancellationToken());
                    reply[0] = turn.text();
                } catch (LlmException e) {
                    throw new InvocationTargetException(e);
                } finally {
                    monitor.done();
                }
            });
            String text = reply[0] == null ? "" : reply[0];
            MessageDialog.openInformation(getShell(), "Kastor",
                    "Conexão OK. Resposta: " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
        } catch (InvocationTargetException e) {
            MessageDialog.openError(getShell(), "Kastor", "Falha: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void performDefaults() {
        maxRows.setSelection(ToolLimits.DEFAULT.maxRows());
        maxChars.setSelection(ToolLimits.DEFAULT.maxResultChars());
        maxIterations.setSelection(SessionSettings.DEFAULT.maxIterations());
        if (allowWrites.isEnabled()) {
            allowWrites.setSelection(true);
        }
        maskColumns.setText("");
        if (audit.isEnabled()) {
            audit.setSelection(true);
        }
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        try {
            ProviderSettings.saveProviders(providers);
            ProviderSettings.setActiveProviderId(activeId);
            for (String id : removedIds) {
                ProviderSettings.removeApiKey(id);
            }
            for (Map.Entry<String, String> e : pendingKeys.entrySet()) {
                ProviderSettings.setApiKey(e.getKey(), e.getValue());
            }
            ProviderSettings.saveLimits(new ToolLimits(maxRows.getSelection(), maxChars.getSelection(),
                    ToolLimits.DEFAULT.maxListItems()), maxIterations.getSelection());
            ProviderSettings.saveSecurity(allowWrites.getSelection(), maskColumns.getText().trim(),
                    audit.getSelection());
            pendingKeys.clear();
            removedIds.clear();
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Kastor", "Não foi possível salvar: " + e.getMessage());
            return false;
        }
    }
}
