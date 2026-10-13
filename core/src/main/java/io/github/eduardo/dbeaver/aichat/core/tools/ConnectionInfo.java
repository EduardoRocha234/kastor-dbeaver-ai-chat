package io.github.eduardo.dbeaver.aichat.core.tools;

public record ConnectionInfo(String id, String name, String driver, String project, boolean connected, boolean readOnly) {
}
