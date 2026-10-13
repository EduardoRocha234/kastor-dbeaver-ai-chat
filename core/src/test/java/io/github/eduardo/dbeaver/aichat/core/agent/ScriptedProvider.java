package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatRequest;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmProvider;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Returns pre-recorded turns (or throws pre-recorded errors) in order. */
final class ScriptedProvider implements LlmProvider {

    private final Deque<Object> script = new ArrayDeque<>();
    final List<ChatRequest> requests = new ArrayList<>();
    AssistantTurn repeat;

    ScriptedProvider then(AssistantTurn turn) {
        script.add(turn);
        return this;
    }

    ScriptedProvider thenFail(LlmException e) {
        script.add(e);
        return this;
    }

    @Override
    public AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException {
        requests.add(request);
        Object next = !script.isEmpty() ? script.poll()
                : repeat != null ? repeat
                : new AssistantTurn("fim", List.of(), StopReason.END_TURN, null, null);
        if (next instanceof LlmException e) {
            throw e;
        }
        AssistantTurn turn = (AssistantTurn) next;
        if (!turn.text().isEmpty()) {
            onTextDelta.accept(turn.text());
        }
        return turn;
    }
}
