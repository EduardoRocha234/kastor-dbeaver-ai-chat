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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
                ChatRequest request = new ChatRequest(config.model(), systemPrompt, compactHistory(conversation.messages()),
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
            listener.onError(friendly(e));
        } catch (RuntimeException | LinkageError e) {
            listener.onError("Erro inesperado: " + e);
        } finally {
            closeDangling();
            listener.onTurnFinished();
        }
    }

    static final int OLD_TOOL_RESULT_CHARS = 1000;

    /**
     * Tool results from earlier questions are only context; cut them so a long chat does not blow the
     * provider's tokens-per-minute limit. Results of the current question stay whole.
     */
    static List<ChatMessage> compactHistory(List<ChatMessage> messages) {
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Role.USER) {
                lastUser = i;
                break;
            }
        }
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (i < lastUser && m.role() == Role.TOOL && m.text().length() > OLD_TOOL_RESULT_CHARS) {
                m = ChatMessage.toolResult(m.toolCallId(),
                        m.text().substring(0, OLD_TOOL_RESULT_CHARS) + "\n[... truncado]", m.error());
            }
            out.add(m);
        }
        return out;
    }

    /** Explains the usual free-tier failures; the provider's own message follows. */
    static String friendly(LlmException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (e.status() == 413 || e.status() == 429 || lower.contains("rate_limit") || lower.contains("rate limit")) {
            return "Limite de uso do provedor atingido (tokens ou requisições por minuto). Aguarde alguns segundos, "
                    + "abra uma nova conversa para reduzir o contexto ou escolha outro modelo. Detalhe: " + msg;
        }
        return msg;
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
