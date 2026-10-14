package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;

/** Called from the worker thread running the turn. */
public interface SessionListener {

    void onAssistantStart();

    void onTextDelta(String text);

    void onAssistantEnd(AssistantTurn turn);

    void onToolStart(ToolCall call);

    void onToolEnd(ToolCall call, ToolOutcome outcome);

    void onNotice(String message);

    /** The per-question model call cap was hit; defaults to a plain notice. */
    default void onIterationLimit(int max) {
        onNotice("Limite de " + max + " chamadas ao modelo atingido nesta pergunta.");
    }

    void onError(String message);

    void onTurnFinished();
}
