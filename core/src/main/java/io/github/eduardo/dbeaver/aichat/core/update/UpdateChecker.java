package io.github.eduardo.dbeaver.aichat.core.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.llm.HttpStreaming;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Asks GitHub for the latest release and tells whether it is newer than the running version. */
public final class UpdateChecker {

    public static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/EduardoRocha234/kastor-dbeaver-ai-chat/releases/latest";

    private static final Pattern SEMVER = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");

    private UpdateChecker() {
    }

    /** A newer release: version without the leading "v", and the release page. */
    public record UpdateInfo(String version, String url) {
    }

    /** Network or parse failures mean "no update known": never bother the user over this. */
    public static Optional<UpdateInfo> check(String currentVersion, HttpClient http, String url) {
        try {
            String body = HttpStreaming.get(http, URI.create(url), Map.of("X-GitHub-Api-Version", "2022-11-28"));
            return parse(body, currentVersion);
        } catch (LlmException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static Optional<UpdateInfo> parse(String body, String currentVersion) {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject release = root.getAsJsonObject();
        String tag = Json.str(release, "tag_name");
        String page = Json.str(release, "html_url");
        boolean prerelease = release.has("prerelease") && release.get("prerelease").getAsBoolean();
        if (tag == null || page == null || prerelease || !page.startsWith("https://")) {
            return Optional.empty();
        }
        if (compare(tag, currentVersion) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new UpdateInfo(tag.startsWith("v") ? tag.substring(1) : tag, page));
    }

    /** Compares X.Y.Z versions (optional leading "v"); unparseable input counts as 0.0.0. */
    public static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return Integer.compare(x[i], y[i]);
            }
        }
        return 0;
    }

    private static int[] numbers(String version) {
        Matcher m = SEMVER.matcher(version == null ? "" : version.trim());
        if (!m.matches()) {
            return new int[3];
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }
}
