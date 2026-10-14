package io.github.eduardo.dbeaver.aichat.core.export;

import java.util.List;

/**
 * One query result to be written to a file. Cells are null, Number, Boolean, LocalDate, LocalDateTime or String.
 * truncated = the query had more rows than the export limit.
 */
public record DataTable(String name, List<String> columns, List<List<Object>> rows, boolean truncated) {
}
