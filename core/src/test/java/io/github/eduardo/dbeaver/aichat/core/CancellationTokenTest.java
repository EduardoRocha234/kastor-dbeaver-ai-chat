package io.github.eduardo.dbeaver.aichat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void runsCallbacksExactlyOnce() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        token.cancel();
        token.cancel();
        assertTrue(token.isCancelled());
        assertEquals(1, calls.get());
    }

    @Test
    void callbackRegisteredAfterCancelRunsImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void closedRegistrationIsNotCalled() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.Registration registration = token.onCancel(calls::incrementAndGet);
        registration.close();
        token.cancel();
        assertEquals(0, calls.get());
    }

    @Test
    void throwIfCancelled() {
        CancellationToken token = new CancellationToken();
        assertDoesNotThrow(token::throwIfCancelled);
        token.cancel();
        assertThrows(CancelledException.class, token::throwIfCancelled);
    }
}
