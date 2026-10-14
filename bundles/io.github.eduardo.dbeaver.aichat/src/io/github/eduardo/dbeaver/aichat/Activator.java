package io.github.eduardo.dbeaver.aichat;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "io.github.eduardo.dbeaver.aichat";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    /** {workspace}/.metadata/.plugins/io.github.eduardo.dbeaver.aichat/audit/audit.jsonl */
    public java.nio.file.Path auditFile() {
        return getStateLocation().append("audit").append("audit.jsonl").toFile().toPath();
    }

    /** {workspace}/.metadata/.plugins/io.github.eduardo.dbeaver.aichat/conversations */
    public java.nio.file.Path conversationsDir() {
        return getStateLocation().append("conversations").toFile().toPath();
    }
}
