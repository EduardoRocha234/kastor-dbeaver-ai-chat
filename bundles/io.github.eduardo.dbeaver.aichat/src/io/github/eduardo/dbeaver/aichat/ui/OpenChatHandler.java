package io.github.eduardo.dbeaver.aichat.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Toolbar/menu action: opens (or focuses) the Kastor panel. */
public class OpenChatHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        try {
            ChatPlacement.dockRightIfNew(window);
            window.getActivePage().showView(ChatView.ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Não foi possível abrir o Kastor", e);
        }
        return null;
    }
}
