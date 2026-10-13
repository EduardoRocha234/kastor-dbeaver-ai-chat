package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

public record ForeignKeyInfo(String name, List<String> columns, String referencedTable, List<String> referencedColumns) {
}
