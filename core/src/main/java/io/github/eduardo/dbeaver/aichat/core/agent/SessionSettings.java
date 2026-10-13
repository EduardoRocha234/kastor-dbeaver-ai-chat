package io.github.eduardo.dbeaver.aichat.core.agent;

/** maxIterations = maximum model calls per user message. */
public record SessionSettings(int maxIterations) {

    public static final SessionSettings DEFAULT = new SessionSettings(25);
}
