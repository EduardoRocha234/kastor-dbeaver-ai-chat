package io.github.eduardo.dbeaver.aichat.settings;

import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import java.util.UUID;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/** Add/edit one provider. apiKey() is null when editing and the key field was left empty (keep current). */
final class ProviderDialog extends Dialog {

    private final ProviderConfig initial;
    private ProviderConfig result;
    private String apiKey;

    private Combo presetCombo;
    private Text nameText;
    private Combo typeCombo;
    private Text urlText;
    private Text modelText;
    private Text keyText;
    private Spinner maxTokensSpinner;
    private Text temperatureText;
    private Button fallbackCheck;

    ProviderDialog(Shell shell, ProviderConfig initial) {
        super(shell);
        this.initial = initial;
    }

    ProviderConfig result() {
        return result;
    }

    String apiKey() {
        return apiKey;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(initial == null ? "Adicionar provedor de IA" : "Editar provedor de IA");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        c.setLayout(new GridLayout(2, false));
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        if (initial == null) {
            label(c, "Modelo pronto:");
            presetCombo = new Combo(c, SWT.DROP_DOWN | SWT.READ_ONLY);
            ProviderPresets.ALL.forEach(p -> presetCombo.add(p.label()));
            presetCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            presetCombo.addListener(SWT.Selection, e -> applyPreset(ProviderPresets.ALL.get(presetCombo.getSelectionIndex())));
        }
        nameText = text(c, "Nome:", SWT.BORDER);
        label(c, "Tipo:");
        typeCombo = new Combo(c, SWT.DROP_DOWN | SWT.READ_ONLY);
        typeCombo.add("OpenAI-compatible");
        typeCombo.add("Anthropic");
        typeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        typeCombo.addListener(SWT.Selection, e -> updateEnablement());
        urlText = text(c, "URL base:", SWT.BORDER);
        modelText = text(c, "Modelo:", SWT.BORDER);
        keyText = text(c, "Chave de API:", SWT.BORDER | SWT.PASSWORD);
        if (initial != null) {
            keyText.setMessage("(vazio = manter a chave atual)");
        }
        label(c, "Máx. tokens (0 = padrão):");
        maxTokensSpinner = new Spinner(c, SWT.BORDER);
        maxTokensSpinner.setMinimum(0);
        maxTokensSpinner.setMaximum(1_000_000);
        maxTokensSpinner.setIncrement(1000);
        temperatureText = text(c, "Temperatura (vazio = padrão):", SWT.BORDER);
        new Label(c, SWT.NONE);
        fallbackCheck = new Button(c, SWT.CHECK);
        fallbackCheck.setText("Fallback no servidor em recusas (Anthropic)");

        if (initial != null) {
            nameText.setText(initial.name());
            typeCombo.select(initial.type() == ProviderType.ANTHROPIC ? 1 : 0);
            urlText.setText(nullToEmpty(initial.baseUrl()));
            modelText.setText(nullToEmpty(initial.model()));
            maxTokensSpinner.setSelection(Math.max(0, initial.maxTokens()));
            temperatureText.setText(initial.temperature() == null ? "" : String.valueOf(initial.temperature()));
            fallbackCheck.setSelection(initial.serverFallback());
        } else {
            presetCombo.select(0);
            applyPreset(ProviderPresets.ALL.get(0));
        }
        updateEnablement();
        return area;
    }

    private void applyPreset(ProviderPresets.Preset preset) {
        nameText.setText(preset.label());
        typeCombo.select(preset.type() == ProviderType.ANTHROPIC ? 1 : 0);
        urlText.setText(preset.baseUrl());
        modelText.setText(preset.model());
        fallbackCheck.setSelection(preset.serverFallback());
        updateEnablement();
    }

    private void updateEnablement() {
        boolean anthropic = typeCombo.getSelectionIndex() == 1;
        temperatureText.setEnabled(!anthropic);
        fallbackCheck.setEnabled(anthropic);
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        String url = urlText.getText().trim();
        String model = modelText.getText().trim();
        if (name.isEmpty() || url.isEmpty() || model.isEmpty()) {
            MessageDialog.openError(getShell(), "Kastor", "Preencha nome, URL base e modelo.");
            return;
        }
        boolean anthropic = typeCombo.getSelectionIndex() == 1;
        Double temperature = null;
        String t = temperatureText.getText().trim().replace(',', '.');
        if (!anthropic && !t.isEmpty()) {
            try {
                temperature = Double.parseDouble(t);
            } catch (NumberFormatException e) {
                MessageDialog.openError(getShell(), "Kastor", "Temperatura inválida: " + t);
                return;
            }
        }
        result = new ProviderConfig(initial != null ? initial.id() : UUID.randomUUID().toString(), name,
                anthropic ? ProviderType.ANTHROPIC : ProviderType.OPENAI_COMPATIBLE, url, model,
                maxTokensSpinner.getSelection(), temperature, anthropic && fallbackCheck.getSelection());
        String key = keyText.getText().trim();
        apiKey = initial != null && key.isEmpty() ? null : key;
        super.okPressed();
    }

    private static void label(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
    }

    private static Text text(Composite parent, String label, int style) {
        label(parent, label);
        Text t = new Text(parent, style);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 320;
        t.setLayoutData(gd);
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
