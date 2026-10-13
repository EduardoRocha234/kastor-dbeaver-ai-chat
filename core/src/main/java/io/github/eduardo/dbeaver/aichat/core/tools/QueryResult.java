package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

/** Cell values already converted to text; null means SQL NULL. */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {
}
