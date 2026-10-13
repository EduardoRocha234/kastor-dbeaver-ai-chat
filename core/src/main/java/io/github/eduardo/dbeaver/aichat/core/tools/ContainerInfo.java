package io.github.eduardo.dbeaver.aichat.core.tools;

/** A database or schema. holdsSchemas = its children are schemas (a database); otherwise it holds tables. */
public record ContainerInfo(String name, boolean holdsSchemas) {
}
