package io.github.eduardo.dbeaver.aichat.core.export;

import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Block;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.TableBlock;

final class Layout {

    /** Tables wider than this are printed in landscape. */
    static final int PORTRAIT_MAX_COLUMNS = 6;

    private Layout() {
    }

    static boolean landscape(ExportDocument doc) {
        for (Block b : doc.blocks()) {
            if (b instanceof TableBlock t && t.columns().size() > PORTRAIT_MAX_COLUMNS) {
                return true;
            }
        }
        return false;
    }
}
