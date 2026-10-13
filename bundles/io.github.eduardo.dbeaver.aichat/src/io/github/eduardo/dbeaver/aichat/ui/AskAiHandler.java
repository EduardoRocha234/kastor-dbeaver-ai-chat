package io.github.eduardo.dbeaver.aichat.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the Kastor view using the active SQL editor's connection and selection as context. */
public class AskAiHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        try {
            ChatPlacement.dockRightIfNew(window);
            ChatView view = (ChatView) window.getActivePage().showView(ChatView.ID);
            view.focusWithEditorContext();
        } catch (PartInitException e) {
            throw new ExecutionException("Não foi possível abrir o Kastor", e);
        }
        return null;
    }
}
