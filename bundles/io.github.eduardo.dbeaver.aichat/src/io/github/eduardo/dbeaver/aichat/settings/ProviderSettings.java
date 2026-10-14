package io.github.eduardo.dbeaver.aichat.settings;

import com.google.gson.reflect.TypeToken;
import io.github.eduardo.dbeaver.aichat.Activator;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.agent.SessionSettings;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.audit.AuditLog;
import io.github.eduardo.dbeaver.aichat.core.audit.JsonlAuditLog;
import io.github.eduardo.dbeaver.aichat.core.tools.ColumnMasker;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolLimits;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolPolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.jkiss.dbeaver.Log;
import org.osgi.service.prefs.BackingStoreException;

/** Provider list and limits in instance preferences; API keys only in Eclipse secure storage. */
public final class ProviderSettings {

    private static final Log log = Log.getLog(ProviderSettings.class);

    private static final String KEY_PROVIDERS = "providers";
    private static final String KEY_ACTIVE = "activeProvider";
    private static final String KEY_MAX_ROWS = "maxRows";
    private static final String KEY_MAX_CHARS = "maxResultChars";
    private static final String KEY_MAX_ITERATIONS = "maxIterations";
    private static final String KEY_ALLOW_WRITES = "allowWrites";
    private static final String KEY_MASK_COLUMNS = "maskColumns";
    private static final String KEY_AUDIT = "auditEnabled";
    private static final String SECURE_NODE = Activator.PLUGIN_ID + "/apiKeys";

    private ProviderSettings() {
    }

    private static IEclipsePreferences prefs() {
        return InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    }

    public static List<ProviderConfig> loadProviders() {
        String json = prefs().get(KEY_PROVIDERS, "[]");
        try {
            List<ProviderConfig> list = Json.GSON.fromJson(json, new TypeToken<List<ProviderConfig>>() {
            }.getType());
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (RuntimeException e) {
            log.error("Invalid Kastor provider settings", e);
            return new ArrayList<>();
        }
    }

    public static void saveProviders(List<ProviderConfig> providers) throws BackingStoreException {
        prefs().put(KEY_PROVIDERS, Json.GSON.toJson(providers));
        prefs().flush();
    }

    public static String activeProviderId() {
        return prefs().get(KEY_ACTIVE, null);
    }

    public static void setActiveProviderId(String id) {
        if (id == null) {
            prefs().remove(KEY_ACTIVE);
        } else {
            prefs().put(KEY_ACTIVE, id);
        }
        try {
            prefs().flush();
        } catch (BackingStoreException e) {
            log.error("Could not save active provider", e);
        }
    }

    public static String apiKey(String providerId) {
        try {
            return secureNode().get(providerId, null);
        } catch (StorageException e) {
            log.error("Could not read API key from secure storage", e);
            return null;
        }
    }

    public static void setApiKey(String providerId, String key) throws StorageException, IOException {
        ISecurePreferences node = secureNode();
        if (key == null || key.isBlank()) {
            node.remove(providerId);
        } else {
            node.put(providerId, key.trim(), true);
        }
        node.flush();
    }

    public static void removeApiKey(String providerId) {
        try {
            ISecurePreferences node = secureNode();
            node.remove(providerId);
            node.flush();
        } catch (IOException e) {
            log.error("Could not remove API key", e);
        }
    }

    public static ToolLimits toolLimits() {
        IEclipsePreferences p = prefs();
        return new ToolLimits(
                p.getInt(KEY_MAX_ROWS, ToolLimits.DEFAULT.maxRows()),
                p.getInt(KEY_MAX_CHARS, ToolLimits.DEFAULT.maxResultChars()),
                ToolLimits.DEFAULT.maxListItems());
    }

    public static SessionSettings sessionSettings() {
        return new SessionSettings(prefs().getInt(KEY_MAX_ITERATIONS, SessionSettings.DEFAULT.maxIterations()));
    }

    public static void saveLimits(ToolLimits limits, int maxIterations) throws BackingStoreException {
        IEclipsePreferences p = prefs();
        p.putInt(KEY_MAX_ROWS, limits.maxRows());
        p.putInt(KEY_MAX_CHARS, limits.maxResultChars());
        p.putInt(KEY_MAX_ITERATIONS, maxIterations);
        p.flush();
    }

    // Admin overrides, set as -D flags in dbeaver.ini. They can only tighten the user's preferences.
    public static final String PROP_WRITES_DISABLED = "kastor.writes.disabled";
    public static final String PROP_MASK_COLUMNS = "kastor.mask.columns";
    public static final String PROP_AUDIT_ENABLED = "kastor.audit.enabled";

    private static final long AUDIT_MAX_BYTES = 5L * 1024 * 1024;
    private static JsonlAuditLog auditLog;

    public static boolean writesLockedByAdmin() {
        return Boolean.getBoolean(PROP_WRITES_DISABLED);
    }

    public static boolean writesAllowed() {
        return !writesLockedByAdmin() && prefs().getBoolean(KEY_ALLOW_WRITES, true);
    }

    /** Patterns set by the user in the preference page. */
    public static String userMaskedColumns() {
        return prefs().get(KEY_MASK_COLUMNS, "");
    }

    public static String adminMaskedColumns() {
        return System.getProperty(PROP_MASK_COLUMNS, "");
    }

    public static boolean auditLockedByAdmin() {
        return Boolean.getBoolean(PROP_AUDIT_ENABLED);
    }

    public static boolean auditEnabled() {
        return auditLockedByAdmin() || prefs().getBoolean(KEY_AUDIT, true);
    }

    public static void saveSecurity(boolean allowWrites, String maskedColumns, boolean audit) throws BackingStoreException {
        IEclipsePreferences p = prefs();
        p.putBoolean(KEY_ALLOW_WRITES, allowWrites);
        p.put(KEY_MASK_COLUMNS, maskedColumns);
        p.putBoolean(KEY_AUDIT, audit);
        p.flush();
    }

    /** Read on every message, so changes in the preference page apply without reopening the chat. */
    public static ToolPolicy toolPolicy() {
        return new ToolPolicy(writesAllowed(),
                ColumnMasker.parse(adminMaskedColumns() + "," + userMaskedColumns()),
                auditEnabled() ? auditLog() : AuditLog.NONE);
    }

    private static synchronized JsonlAuditLog auditLog() {
        if (auditLog == null) {
            auditLog = new JsonlAuditLog(Activator.getDefault().auditFile(), AUDIT_MAX_BYTES);
        }
        return auditLog;
    }

    private static ISecurePreferences secureNode() {
        return SecurePreferencesFactory.getDefault().node(SECURE_NODE);
    }
}
