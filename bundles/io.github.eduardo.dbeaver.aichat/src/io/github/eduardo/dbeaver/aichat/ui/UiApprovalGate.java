package io.github.eduardo.dbeaver.aichat.ui;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalGate;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalRequest;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.eclipse.swt.widgets.Display;

/** Shows an approval card in the chat and blocks the worker thread until the user clicks. */
final class UiApprovalGate implements ApprovalGate {

    private final Display display;
    private final ChatBrowserBridge bridge;
    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    UiApprovalGate(Display display, ChatBrowserBridge bridge) {
        this.display = display;
        this.bridge = bridge;
    }

    @Override
    public Decision requestApproval(ApprovalRequest request, CancellationToken cancel) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(id, future);
        ui(() -> bridge.addApproval(id, request));
        try (CancellationToken.Registration registration = cancel.onCancel(() -> future.cancel(false))) {
            return future.get();
        } catch (CancellationException e) {
            ui(() -> bridge.closeApproval(id, "Cancelado"));
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            return Decision.REJECTED;
        } finally {
            pending.remove(id);
        }
    }

    void complete(String id, Decision decision) {
        CompletableFuture<Decision> future = pending.get(id);
        if (future != null) {
            future.complete(decision);
        }
    }

    void rejectAll() {
        pending.values().forEach(f -> f.complete(Decision.REJECTED));
    }

    private void ui(Runnable r) {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!bridge.isDisposed()) {
                    r.run();
                }
            });
        }
    }
}
