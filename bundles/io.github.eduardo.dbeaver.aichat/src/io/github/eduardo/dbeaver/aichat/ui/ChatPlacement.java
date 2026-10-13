package io.github.eduardo.dbeaver.aichat.ui;

import java.util.List;
import org.eclipse.e4.ui.model.application.ui.advanced.MPerspective;
import org.eclipse.e4.ui.model.application.ui.advanced.MPlaceholder;
import org.eclipse.e4.ui.model.application.ui.basic.MPartStack;
import org.eclipse.e4.ui.model.application.ui.basic.MWindow;
import org.eclipse.e4.ui.workbench.modeling.EModelService;
import org.eclipse.e4.ui.workbench.modeling.EPartService;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;

/**
 * Docks the chat to the right of the editor area the first time it is opened in a perspective,
 * like the chat panel in VS Code. The perspective extension in plugin.xml only applies to new or
 * reset perspectives; this covers existing workspaces. Once placed, the user's layout wins.
 */
final class ChatPlacement {

    private static final Log log = Log.getLog(ChatPlacement.class);
    private static final String EDITOR_AREA_ID = "org.eclipse.ui.editorss";
    private static final String STACK_ID = "io.github.eduardo.dbeaver.aichat.stack";
    private static final float WIDTH_RATIO = 0.30f;

    private ChatPlacement() {
    }

    static void dockRightIfNew(IWorkbenchWindow window) {
        try {
            MWindow model = window.getService(MWindow.class);
            EModelService models = window.getService(EModelService.class);
            EPartService parts = window.getService(EPartService.class);
            if (model == null || models == null || parts == null) {
                return;
            }
            MPerspective perspective = models.getActivePerspective(model);
            if (perspective == null
                    || !models.findElements(perspective, ChatView.ID, MPlaceholder.class, null).isEmpty()) {
                return;
            }
            List<MPlaceholder> editorArea = models.findElements(perspective, EDITOR_AREA_ID, MPlaceholder.class, null);
            if (editorArea.isEmpty()) {
                return;
            }
            MPartStack stack = models.createModelElement(MPartStack.class);
            stack.setElementId(STACK_ID);
            MPlaceholder placeholder = parts.createSharedPart(ChatView.ID);
            stack.getChildren().add(placeholder);
            stack.setSelectedElement(placeholder);
            models.insert(stack, editorArea.get(0), EModelService.RIGHT_OF, WIDTH_RATIO);
        } catch (RuntimeException | LinkageError e) {
            // showView still opens the chat, just wherever the workbench puts new views.
            log.warn("AI Chat: não foi possível acoplar o painel à direita", e);
        }
    }
}
