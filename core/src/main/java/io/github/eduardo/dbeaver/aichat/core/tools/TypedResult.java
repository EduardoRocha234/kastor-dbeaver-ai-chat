package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

/**
 * Rows with their values kept typed, for file export. Each cell is null (SQL NULL), a Number, a Boolean,
 * a LocalDate, a LocalDateTime or a String (anything else, already converted to text).
 */
public record TypedResult(List<String> columns, List<List<Object>> rows, boolean truncated) {
}
