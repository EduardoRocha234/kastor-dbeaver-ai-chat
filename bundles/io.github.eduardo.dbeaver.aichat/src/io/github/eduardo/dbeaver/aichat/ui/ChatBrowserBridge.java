package io.github.eduardo.dbeaver.aichat.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.render.MarkdownRenderer;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.jkiss.dbeaver.Log;

/**
 * Owns the Browser widget: Java -> JS calls (queued until the page loads) and JS -> Java callbacks.
 *
 * <p>Moving the view to another stack re-parents the widget, and the Edge (WebView2) browser of
 * older SWT releases stops painting after that. So every call since the last {@code clear} is kept
 * in a journal; when the widget's ancestors change, the browser is recreated and the journal
 * replayed, which also brings back pending approval cards.
 */
final class ChatBrowserBridge {

    interface Callbacks {
        void approve(String id);

        void reject(String id);

        void insertSql(String sql);

        void openSql(String sql);

        void copy(String text);

        /** The user pressed Enter or the send button in the page's composer. */
        void send(String text);

        void stop();

        /** An "@" query typed in the composer; answer with {@link ChatBrowserBridge#showMentions}. */
        void mention(int seq, String query);

        void selectProvider(String id);

        /** id "" = the active editor's connection. */
        void selectConnection(String id);

        void toggleContext(boolean include);

        void newConversation();

        /** The sidebar opened; answer with {@link ChatBrowserBridge#setConversations}. */
        void listConversations();

        void openConversation(String id);

        void deleteConversation(String id);

        void openSettings();
    }

    private static final Log log = Log.getLog(ChatBrowserBridge.class);

    private final Composite host;
    private final Callbacks callbacks;
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    private final List<String> journal = new ArrayList<>();
    private final Map<String, Integer> htmlEntries = new HashMap<>();
    private final List<String> pending = new ArrayList<>();
    private List<Composite> ancestors;
    private Browser browser;
    private boolean ready;
    private boolean pageLoaded;
    /** Latest call of each page-state function (theme, selectors, title...), re-applied after a reload. */
    private final Map<String, String> state = new LinkedHashMap<>();

    ChatBrowserBridge(Composite parent, Callbacks callbacks) {
        this.callbacks = callbacks;
        host = new Composite(parent, SWT.NONE);
        host.setLayout(new FillLayout());
        createBrowserWidget();
        ancestors = ancestors(host);
        host.addListener(SWT.Resize, e -> recreateIfReparented());
        host.addListener(SWT.Show, e -> recreateIfReparented());
    }

    private void createBrowserWidget() {
        Browser created = createBrowser(host);
        browser = created;
        ready = false;
        pageLoaded = false;
        function("aichatApprove", callbacks::approve);
        function("aichatReject", callbacks::reject);
        function("aichatInsertSql", callbacks::insertSql);
        function("aichatOpenSql", callbacks::openSql);
        function("aichatCopy", callbacks::copy);
        function("aichatOpenLink", ChatBrowserBridge::openLink);
        function("aichatSend", callbacks::send);
        function("aichatStop", arg -> callbacks.stop());
        function("aichatSelectProvider", callbacks::selectProvider);
        function("aichatSelectConnection", callbacks::selectConnection);
        function("aichatToggleContext", arg -> callbacks.toggleContext(Boolean.parseBoolean(arg)));
        function("aichatNewConversation", arg -> callbacks.newConversation());
        function("aichatListConversations", arg -> callbacks.listConversations());
        function("aichatOpenConversation", callbacks::openConversation);
        function("aichatDeleteConversation", callbacks::deleteConversation);
        function("aichatOpenSettings", arg -> callbacks.openSettings());
        function("aichatMention", arg -> {
            JsonObject request = Json.parseObject(arg);
            callbacks.mention(request.get("seq").getAsInt(), request.get("query").getAsString());
        });
        browser.addProgressListener(ProgressListener.completedAdapter(e -> {
            if (created != browser) {
                return;
            }
            log.info("Kastor: página do chat carregada (" + browser.getBrowserType() + ")");
            pageLoaded = true;
            ready = true;
            pending.addAll(0, state.values());
            for (String script : pending) {
                if (!browser.execute(script)) {
                    log.info("Kastor: script falhou ao carregar a página: " + abbreviate(script));
                }
            }
            pending.clear();
        }));
        // Only the initial setText load may navigate; anything later (even about:blank from a
        // sanitized link) would reload an empty page and lose the conversation and approvals.
        // setText arrives as "about:blank" (IE, WebKit) or "data:text/html..." (Edge NavigateToString).
        browser.addLocationListener(LocationListener.changingAdapter(e -> {
            if (pageLoaded || !isInitialPage(e.location)) {
                log.info("Kastor: navegação bloqueada: " + abbreviate(String.valueOf(e.location)));
                e.doit = false;
            }
        }));
        browser.setText(loadPage());
        log.info("Kastor: navegador criado (" + browser.getBrowserType() + ")");
    }

    private static boolean isInitialPage(String location) {
        return location == null || location.startsWith("about:") || location.startsWith("data:text/html");
    }

    private void recreateIfReparented() {
        if (host.isDisposed()) {
            return;
        }
        List<Composite> now = ancestors(host);
        if (now.equals(ancestors)) {
            return;
        }
        ancestors = now;
        log.info("Kastor: painel movido; recriando o navegador");
        browser.dispose();
        createBrowserWidget();
        pending.clear();
        pending.addAll(journal);
        host.layout(true, true);
    }

    /** Identity list of the widget's parents up to the shell. */
    private static List<Composite> ancestors(Control control) {
        List<Composite> list = new ArrayList<>();
        for (Composite p = control.getParent(); p != null; p = p.getParent()) {
            list.add(p);
        }
        return list;
    }

    Control control() {
        return host;
    }

    boolean isDisposed() {
        return host.isDisposed();
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

    void setTheme(boolean dark) {
        stateCall("setTheme", dark);
    }

    void setRunning(boolean running) {
        stateCall("setRunning", running);
    }

    /** entries: {id, label}. */
    void setProviders(JsonArray entries, String activeId) {
        stateCall("setProviders", entries, activeId);
    }

    /** entries: {id, label}; id "" = the active editor's connection. */
    void setConnections(JsonArray entries, String selectedId) {
        stateCall("setConnections", entries, selectedId);
    }

    void setEditorContext(String text, boolean include) {
        stateCall("setEditorContext", text, include);
    }

    /** Adds "@path" mentions at the composer's caret. */
    void insertMentions(List<String> paths) {
        if (!paths.isEmpty()) {
            transientCall("insertMentions", paths);
        }
    }

    void setVersion(String version) {
        stateCall("setVersion", version);
    }

    void setTitle(String title) {
        stateCall("setTitle", title);
    }

    /** entries: {id, title, updatedAt}. */
    void setConversations(JsonArray entries, String activeId) {
        transientCall("setConversations", entries, activeId);
    }

    private void stateCall(String function, Object... args) {
        state.put(function, script(function, args));
        transientCall(function, args);
    }

    void clearInput() {
        transientCall("clearInput");
    }

    void setInput(String text, boolean selectAll) {
        transientCall("setInput", text, selectAll);
    }

    /** Focuses the browser widget and the composer inside it. */
    void focusInput() {
        if (!browser.isDisposed()) {
            browser.setFocus();
        }
        transientCall("focusInput");
    }

    void showMentions(int seq, JsonArray entries) {
        transientCall("showMentions", seq, entries);
    }

    /** UI state outside the journal: sent now (or once the page loads) but never replayed. */
    private void transientCall(String function, Object... args) {
        if (host.isDisposed() || browser.isDisposed()) {
            return;
        }
        String script = script(function, args);
        if (!ready) {
            pending.add(script);
        } else if (!browser.execute(script)) {
            log.info("Kastor: script falhou: " + function);
        }
    }

    private static String script(String function, Object... args) {
        StringBuilder sb = new StringBuilder("ChatUI.").append(function).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.GSON.toJson(args[i]));
        }
        return sb.append(");").toString();
    }

    private void call(String function, Object... args) {
        if (host.isDisposed() || browser.isDisposed()) {
            return;
        }
        String script = script(function, args);
        record(function, args, script);
        if (!ready) {
            pending.add(script);
        } else if (!browser.execute(script)) {
            log.info("Kastor: script falhou: " + function);
        }
    }

    /** Keeps the journal replayable: clear resets it, re-renders of a message replace the old one. */
    private void record(String function, Object[] args, String script) {
        if ("clear".equals(function)) {
            journal.clear();
            htmlEntries.clear();
            return;
        }
        if ("setAssistantHtml".equals(function)) {
            Integer index = htmlEntries.get(String.valueOf(args[0]));
            if (index != null) {
                journal.set(index, script);
                return;
            }
            htmlEntries.put(String.valueOf(args[0]), journal.size());
        }
        journal.add(script);
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "...";
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

    private static void openLink(String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            Program.launch(url);
        }
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
