package io.github.eduardo.dbeaver.aichat.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation shared by the HTTP stream, SQL execution and approval waits. */
public final class CancellationToken {

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable callback : callbacks) {
                if (callbacks.remove(callback)) {
                    runQuietly(callback);
                }
            }
        }
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }

    /** Runs the callback on cancel (immediately if already cancelled). */
    public Registration onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            runQuietly(callback);
        }
        return () -> callbacks.remove(callback);
    }

    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // cancellation callbacks are best effort
        }
    }
}
