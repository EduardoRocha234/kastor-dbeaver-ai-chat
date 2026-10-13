package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import java.util.function.Consumer;

public interface LlmProvider {

    /**
     * Streams one model response. Text deltas go to onTextDelta as they arrive.
     *
     * @throws io.github.eduardo.dbeaver.aichat.core.CancelledException when the token is cancelled
     */
    AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException;
}
