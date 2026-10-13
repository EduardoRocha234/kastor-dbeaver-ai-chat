package io.github.eduardo.dbeaver.aichat.ui;

import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.render.MarkdownRenderer;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.jkiss.dbeaver.Log;

/** Owns the Browser widget: Java -> JS calls (queued until the page loads) and JS -> Java callbacks. */
final class ChatBrowserBridge {

    interface Callbacks {
        void approve(String id);

        void reject(String id);

        void insertSql(String sql);

        void openSql(String sql);

        void copy(String text);
    }

    private static final Log log = Log.getLog(ChatBrowserBridge.class);

    private final Browser browser;
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    private final List<String> pending = new ArrayList<>();
    private boolean ready;

    ChatBrowserBridge(Composite parent, Callbacks callbacks) {
        browser = createBrowser(parent);
        function("aichatApprove", callbacks::approve);
        function("aichatReject", callbacks::reject);
        function("aichatInsertSql", callbacks::insertSql);
        function("aichatOpenSql", callbacks::openSql);
        function("aichatCopy", callbacks::copy);
        browser.addProgressListener(ProgressListener.completedAdapter(e -> {
            ready = true;
            for (String script : pending) {
                browser.execute(script);
            }
            pending.clear();
        }));
        browser.addLocationListener(LocationListener.changingAdapter(e -> {
            if (e.location != null && !e.location.startsWith("about:")) {
                e.doit = false;
            }
        }));
        browser.setText(loadPage());
    }

    Control control() {
        return browser;
    }

    boolean isDisposed() {
        return browser.isDisposed();
    }

    void clear() {
        call("clear");
    }

    void addUser(String text) {
        call("addUser", text);
    }

    void startAssistant(String id) {
        call("startAssistant", id);
    }

    void setAssistantMarkdown(String id, String text) {
        call("setAssistantHtml", id, markdown.toHtml(text));
    }

    void finishAssistant(String id) {
        call("finishAssistant", id);
    }

    void addTool(String id, String name, String argumentsJson) {
        call("addTool", id, name, argumentsJson);
    }

    void setToolResult(String id, String text, boolean error) {
        call("setToolResult", id, text, error);
    }

    void addApproval(String id, ApprovalRequest request) {
        call("addApproval", id, request.connectionName(), request.sql(), request.reason() == null ? "" : request.reason());
    }

    void closeApproval(String id, String label) {
        call("closeApproval", id, label);
    }

    void addNotice(String text) {
        call("addNotice", text);
    }

    void addError(String text) {
        call("addError", text);
    }

    private void call(String function, Object... args) {
        if (browser.isDisposed()) {
            return;
        }
        StringBuilder sb = new StringBuilder("ChatUI.").append(function).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.GSON.toJson(args[i]));
        }
        String script = sb.append(");").toString();
        if (!ready) {
            pending.add(script);
        } else if (!browser.execute(script)) {
            log.debug("Chat script failed: " + function);
        }
    }

    private void function(String name, Consumer<String> handler) {
        new BrowserFunction(browser, name) {
            @Override
            public Object function(Object[] arguments) {
                String arg = arguments.length > 0 && arguments[0] != null ? arguments[0].toString() : "";
                getBrowser().getDisplay().asyncExec(() -> handler.accept(arg));
                return null;
            }
        };
    }

    private static Browser createBrowser(Composite parent) {
        if ("win32".equals(SWT.getPlatform())) {
            try {
                return new Browser(parent, SWT.EDGE);
            } catch (SWTError e) {
                log.warn("Edge (WebView2) indisponível; usando o navegador padrão do SWT", e);
            }
        }
        return new Browser(parent, SWT.NONE);
    }

    private static String loadPage() {
        return resource("chat.html").replace("/*CSS*/", resource("chat.css")).replace("/*JS*/", resource("chat.js"));
    }

    private static String resource(String name) {
        try (InputStream in = ChatBrowserBridge.class.getResourceAsStream("/resources/chat/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Recurso ausente: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao ler recurso " + name, e);
        }
    }
}
