package io.github.eduardo.dbeaver.aichat.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSWrapper;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;

/**
 * "Referenciar no Kastor": puts "@db.schema.table" for the selected navigator objects (or the object open in the
 * active editor) into the chat input, with the object's connection selected.
 */
public class ReferenceInKastorHandler extends AbstractHandler {

    /** A mention path and the schema path it names or sits in (null for databases and other objects). */
    record Reference(String path, String schemaPath) {
    }

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        DBPDataSourceContainer container = null;
        Set<Reference> references = new LinkedHashSet<>();
        for (Object element : selectedElements(event)) {
            if (element instanceof DBNDataSource) {
                container = container == null ? ((DBNDataSource) element).getDataSourceContainer() : container;
                continue;
            }
            DBSObject object = element instanceof DBSWrapper ? ((DBSWrapper) element).getObject()
                    : element instanceof DBSObject ? (DBSObject) element : null;
            Reference ref = object == null ? null : reference(object);
            if (ref == null || object.getDataSource() == null) {
                continue;
            }
            DBPDataSourceContainer owner = object.getDataSource().getContainer();
            if (container == null) {
                container = owner;
            }
            if (owner == container) {
                references.add(ref);
            }
        }
        if (container == null) {
            return null;
        }
        try {
            ChatPlacement.dockRightIfNew(window);
            ChatView view = (ChatView) window.getActivePage().showView(ChatView.ID);
            view.reference(container.getId(), new ArrayList<>(references));
        } catch (PartInitException e) {
            throw new ExecutionException("Não foi possível abrir o Kastor", e);
        }
        return null;
    }

    /** Navigator selection when the navigator is active, otherwise the object of the active database editor. */
    private static List<Object> selectedElements(ExecutionEvent event) {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        if (part instanceof IEditorPart) {
            IEditorInput input = ((IEditorPart) part).getEditorInput();
            if (input instanceof IDatabaseEditorInput) {
                DBSObject object = ((IDatabaseEditorInput) input).getDatabaseObject();
                return object == null ? List.of() : List.of(object);
            }
        }
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (selection instanceof IStructuredSelection) {
            return ((IStructuredSelection) selection).toList();
        }
        return List.of();
    }

    /**
     * The object's path in the same form the gateway resolves ("db.schema.table"): names of the catalogs, schemas
     * and the entity above it, up to the data source. A column points at its table.
     */
    static Reference reference(DBSObject object) {
        DBSObject leaf = object instanceof DBSEntityAttribute ? object.getParentObject() : object;
        if (leaf == null || leaf instanceof DBPDataSource) {
            return null;
        }
        Deque<String> parts = new ArrayDeque<>();
        parts.addFirst(leaf.getName());
        Deque<String> containerParts = new ArrayDeque<>();
        for (DBSObject p = leaf.getParentObject(); p != null && !(p instanceof DBPDataSource); p = p.getParentObject()) {
            if (p instanceof DBSCatalog || p instanceof DBSSchema || p instanceof DBSEntity) {
                parts.addFirst(p.getName());
            }
            if (p instanceof DBSCatalog || p instanceof DBSSchema) {
                containerParts.addFirst(p.getName());
            }
        }
        String path = String.join(".", parts);
        String schemaPath = leaf instanceof DBSSchema ? path
                : leaf instanceof DBSEntity && !containerParts.isEmpty() ? String.join(".", containerParts) : null;
        return new Reference(path, schemaPath);
    }
}
