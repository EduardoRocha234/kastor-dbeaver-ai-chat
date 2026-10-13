package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

public record TableDescription(String schema, String name, String type, List<ColumnInfo> columns,
        List<String> primaryKey, List<ForeignKeyInfo> foreignKeys, List<IndexInfo> indexes) {
}
