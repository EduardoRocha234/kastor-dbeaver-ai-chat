package io.github.eduardo.dbeaver.aichat.core.update;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.llm.FakeHttpServer;
import io.github.eduardo.dbeaver.aichat.core.llm.HttpStreaming;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UpdateCheckerTest {

    private static final String RELEASE =
            "{\"tag_name\":\"v0.2.0\",\"html_url\":\"https://github.com/x/y/releases/tag/v0.2.0\",\"prerelease\":false}";

    @Test
    void comparesNumerically() {
        assertTrue(UpdateChecker.compare("0.1.1", "0.1.0") > 0);
        assertTrue(UpdateChecker.compare("0.10.0", "0.9.0") > 0);
        assertEquals(0, UpdateChecker.compare("v1.2.3", "1.2.3"));
        assertTrue(UpdateChecker.compare("0.1.0", "0.2.0") < 0);
    }

    @Test
    void newerReleaseIsReported() {
        Optional<UpdateChecker.UpdateInfo> info = UpdateChecker.parse(RELEASE, "0.1.0");
        assertEquals("0.2.0", info.orElseThrow().version());
        assertTrue(info.get().url().endsWith("v0.2.0"));
    }

    @Test
    void sameOrOlderReleaseIsIgnored() {
        assertTrue(UpdateChecker.parse(RELEASE, "0.2.0").isEmpty());
        assertTrue(UpdateChecker.parse(RELEASE, "0.3.0").isEmpty());
    }

    @Test
    void prereleaseAndNonHttpsAreIgnored() {
        assertTrue(UpdateChecker.parse(RELEASE.replace("false", "true"), "0.1.0").isEmpty());
        assertTrue(UpdateChecker.parse(RELEASE.replace("https://", "file://"), "0.1.0").isEmpty());
    }

    @Test
    void garbageAndHttpFailureMeanNoUpdate() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(404, "{\"message\":\"Not Found\"}");
            assertTrue(UpdateChecker.check("0.1.0", HttpStreaming.defaultClient(), server.baseUrl() + "/r").isEmpty());
            server.enqueue(200, RELEASE);
            assertTrue(UpdateChecker.check("0.1.0", HttpStreaming.defaultClient(), server.baseUrl() + "/r").isPresent());
        }
    }
}
