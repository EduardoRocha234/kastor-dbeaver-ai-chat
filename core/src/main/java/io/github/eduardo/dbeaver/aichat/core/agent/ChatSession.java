package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatRequest;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmProvider;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.Role;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolContext;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Runs one user turn: model -> tools -> model ... until a final answer, a stop, or the iteration limit. */
public final class ChatSession {

    private final Conversation conversation;
    private final LlmProvider provider;
    private final ProviderConfig config;
    private final ToolRegistry tools;
    private final SessionSettings settings;

    public ChatSession(Conversation conversation, LlmProvider provider, ProviderConfig config, ToolRegistry tools,
            SessionSettings settings) {
        this.conversation = conversation;
        this.provider = provider;
        this.config = config;
        this.tools = tools;
        this.settings = settings;
    }

    public void runTurn(String userText, String systemPrompt, ToolContext toolContext, SessionListener listener) {
        CancellationToken cancel = toolContext.cancel();
        conversation.add(ChatMessage.user(userText));
        try {
            for (int iteration = 0; iteration < settings.maxIterations(); iteration++) {
                cancel.throwIfCancelled();
                ChatRequest request = new ChatRequest(config.model(), systemPrompt, conversation.messages(),
                        tools.specs(), config.maxTokens(), config.temperature());
                listener.onAssistantStart();
                AssistantTurn turn = provider.stream(request, listener::onTextDelta, cancel);
                if (!turn.isEmpty()) {
                    conversation.add(ChatMessage.assistant(turn));
                }
                listener.onAssistantEnd(turn);
                if (turn.toolCalls().isEmpty()) {
                    notifyStop(turn.stopReason(), listener);
                    return;
                }
                if (turn.stopReason() != StopReason.TOOL_USE) {
                    closeAll(turn.toolCalls(), "Não executado: a resposta do modelo foi interrompida ("
                            + turn.stopReason() + ").");
                    notifyStop(turn.stopReason(), listener);
                    return;
                }
                runTools(turn.toolCalls(), toolContext, listener);
            }
            listener.onNotice("Limite de " + settings.maxIterations() + " chamadas ao modelo atingido nesta pergunta.");
        } catch (CancelledException e) {
            listener.onNotice("Cancelado.");
        } catch (LlmException e) {
            listener.onError(e.getMessage());
        } catch (RuntimeException | LinkageError e) {
            listener.onError("Erro inesperado: " + e);
        } finally {
            closeDangling();
            listener.onTurnFinished();
        }
    }

    /**
     * Every tool call of the last assistant message must have a result, or the next request is
     * rejected by the provider (HTTP 400). Normal paths already guarantee it; this covers the rest.
     */
    private void closeDangling() {
        List<ChatMessage> messages = conversation.messages();
        Set<String> answered = new HashSet<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == Role.TOOL) {
                answered.add(message.toolCallId());
            } else if (message.role() == Role.ASSISTANT) {
                for (ToolCall call : message.toolCalls()) {
                    if (!answered.contains(call.id())) {
                        conversation.add(ChatMessage.toolResult(call.id(), "Não executado: erro interno.", true));
                    }
                }
                return;
            } else {
                return;
            }
        }
    }

    private void runTools(List<ToolCall> calls, ToolContext ctx, SessionListener listener) {
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (ctx.cancel().isCancelled()) {
                closeAll(calls.subList(i, calls.size()), "Cancelado pelo usuário.");
                throw new CancelledException();
            }
            listener.onToolStart(call);
            ToolOutcome outcome;
            try {
                outcome = tools.execute(call, ctx);
            } catch (CancelledException e) {
                outcome = ToolOutcome.error("Cancelado pelo usuário.");
                conversation.add(ChatMessage.toolResult(call.id(), outcome.content(), true));
                listener.onToolEnd(call, outcome);
                closeAll(calls.subList(i + 1, calls.size()), "Cancelado pelo usuário.");
                throw e;
            }
            conversation.add(ChatMessage.toolResult(call.id(), outcome.content(), outcome.error()));
            listener.onToolEnd(call, outcome);
        }
        ctx.cancel().throwIfCancelled();
    }

    private void closeAll(List<ToolCall> calls, String reason) {
        for (ToolCall call : calls) {
            conversation.add(ChatMessage.toolResult(call.id(), reason, true));
        }
    }

    private static void notifyStop(StopReason reason, SessionListener listener) {
        if (reason == StopReason.MAX_TOKENS) {
            listener.onNotice("Resposta cortada pelo limite de tokens (max_tokens).");
        } else if (reason == StopReason.REFUSAL) {
            listener.onNotice("O modelo recusou esta solicitação.");
        }
    }
}
