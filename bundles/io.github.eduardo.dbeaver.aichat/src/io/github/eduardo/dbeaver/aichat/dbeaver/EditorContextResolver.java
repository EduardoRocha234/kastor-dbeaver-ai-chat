package io.github.eduardo.dbeaver.aichat.dbeaver;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

public final class EditorContextResolver {

    private static final Log log = Log.getLog(EditorContextResolver.class);

    private EditorContextResolver() {
    }

    /** Context of the last active SQL editor in the window, or null if there is none. */
    public static EditorContext fromActiveEditor(IWorkbenchWindow window) {
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart part = page == null ? null : page.getActiveEditor();
        if (!(part instanceof SQLEditor)) {
            return null;
        }
        SQLEditor editor = (SQLEditor) part;
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        String schema = null;
        try {
            DBCExecutionContext context = editor.getExecutionContext();
            var defaults = context == null ? null : context.getContextDefaults();
            if (defaults != null) {
                var catalog = defaults.getDefaultCatalog();
                var defaultSchema = defaults.getDefaultSchema();
                if (defaultSchema != null) {
                    schema = catalog != null ? catalog.getName() + "." + defaultSchema.getName() : defaultSchema.getName();
                } else if (catalog != null) {
                    schema = catalog.getName();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("Could not read editor default schema", e);
        }
        String selected = null;
        ISelection selection = editor.getSelectionProvider().getSelection();
        if (selection instanceof ITextSelection && ((ITextSelection) selection).getLength() > 0) {
            selected = ((ITextSelection) selection).getText();
        }
        return new EditorContext(container, schema, selected);
    }
}
