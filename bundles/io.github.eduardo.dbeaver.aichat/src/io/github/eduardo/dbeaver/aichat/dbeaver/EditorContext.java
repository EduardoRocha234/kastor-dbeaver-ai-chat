package io.github.eduardo.dbeaver.aichat.dbeaver;

import org.jkiss.dbeaver.model.DBPDataSourceContainer;

/** Snapshot of the active SQL editor. container may be null (editor without connection). */
public record EditorContext(DBPDataSourceContainer container, String schema, String selectedText) {
}
