package io.github.eduardo.dbeaver.aichat.dbeaver;

import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.Optional;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.jdbc.JDBCSession;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryType;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLNavigatorContext;

/**
 * Every DBeaver call that is likely to change between versions lives here, guarded so that a
 * missing method degrades to a simpler behavior instead of breaking the plugin.
 */
public final class DBeaverCompat {

    private static final Log log = Log.getLog(DBeaverCompat.class);

    private DBeaverCompat() {
    }

    /** 24.x spells it {@code isModifiyng()}, newer releases {@code isModifying()}; null when neither exists. */
    private static Boolean isModifying(SQLQuery query) {
        for (String name : new String[] {"isModifying", "isModifiyng"}) {
            try {
                return (Boolean) SQLQuery.class.getMethod(name).invoke(query);
            } catch (NoSuchMethodException e) {
                // try the other spelling
            } catch (ReflectiveOperationException e) {
                log.debug("SQLQuery." + name + " failed", e);
                return null;
            }
        }
        return null;
    }

    /**
     * Asks the database itself to refuse writes on this session (JDBC {@code setReadOnly}); a
     * second barrier behind the SQL classifier. Returns false when the driver can't do it.
     */
    public static boolean setReadOnly(DBCSession session, boolean readOnly) {
        if (!(session instanceof JDBCSession)) {
            return false;
        }
        try {
            ((JDBCSession) session).setReadOnly(readOnly);
            return true;
        } catch (Exception | LinkageError e) {
            log.debug("setReadOnly(" + readOnly + ") not supported", e);
            return false;
        }
    }

    /** Closes an execution context; {@code close()} declares {@code DBException} in newer releases. */
    public static void closeQuietly(DBCExecutionContext context) {
        try {
            context.close();
        } catch (Exception | LinkageError e) {
            log.debug("Error closing AI Chat context", e);
        }
    }

    /** DBeaver's own SQL parser as a second opinion; empty when it has no opinion or is unavailable. */
    public static Optional<SqlKind> classifyNative(DBPDataSource dataSource, String sql) {
        try {
            SQLQuery query = new SQLQuery(dataSource, sql);
            SQLQueryType type = query.getType();
            if (type == null || type == SQLQueryType.UNKNOWN) {
                return Optional.empty();
            }
            if (type == SQLQueryType.SELECT) {
                Boolean modifying = isModifying(query);
                if (modifying == null) {
                    return Optional.empty();
                }
                return Optional.of(modifying ? SqlKind.WRITE : SqlKind.READ);
            }
            return Optional.of(SqlKind.WRITE);
        } catch (RuntimeException | LinkageError e) {
            log.debug("Native SQL classification unavailable", e);
            return Optional.empty();
        }
    }

    /** Inserts at the cursor (replacing the selection) of the active SQL editor. */
    public static boolean insertIntoActiveEditor(IWorkbenchWindow window, String sql) {
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart part = page == null ? null : page.getActiveEditor();
        if (!(part instanceof SQLEditor)) {
            return false;
        }
        SQLEditor editor = (SQLEditor) part;
        try {
            IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            ISelection selection = editor.getSelectionProvider().getSelection();
            int offset = document.getLength();
            int length = 0;
            if (selection instanceof ITextSelection) {
                offset = ((ITextSelection) selection).getOffset();
                length = ((ITextSelection) selection).getLength();
            }
            document.replace(offset, length, sql);
            page.activate(editor);
            return true;
        } catch (BadLocationException | RuntimeException | LinkageError e) {
            log.debug("Could not insert SQL into editor", e);
            return false;
        }
    }

    /** Opens a new SQL console bound to the connection with the given text. */
    public static boolean openInNewEditor(IWorkbenchWindow window, DBPDataSourceContainer container, String sql) {
        try {
            SQLNavigatorContext context = container == null ? new SQLNavigatorContext() : new SQLNavigatorContext(container);
            return SQLEditorHandlerOpenEditor.openSQLConsole(window, context, "AI Chat", sql) != null;
        } catch (RuntimeException | LinkageError e) {
            log.debug("Could not open SQL console", e);
            return false;
        }
    }
}
