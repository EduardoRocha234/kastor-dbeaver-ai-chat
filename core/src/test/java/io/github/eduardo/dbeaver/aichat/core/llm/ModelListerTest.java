package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModelListerTest {

    @Test
    void parsesSortedUniqueIds() {
        assertEquals(List.of("a-model", "openai/gpt-oss-120b", "Zeta"),
                ModelLister.parse("{\"data\":[{\"id\":\"Zeta\"},{\"id\":\"openai/gpt-oss-120b\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"}]}"));
    }

    @Test
    void listsOpenAiCompatibleWithBearer() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}");
            ProviderConfig c = new ProviderConfig("p", "G", ProviderType.OPENAI_COMPATIBLE,
                    server.baseUrl() + "/openai/v1/", "m1", 0, null, false);
            assertEquals(List.of("m1", "m2"), ModelLister.list(c, "k", HttpStreaming.defaultClient()));
            assertEquals("/openai/v1/models", server.lastPath);
            assertEquals("Bearer k", server.lastHeaders.getFirst("Authorization"));
        }
    }

    @Test
    void listsAnthropicWithKeyHeader() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "{\"data\":[{\"id\":\"claude-x\"}]}");
            ProviderConfig c = new ProviderConfig("p", "A", ProviderType.ANTHROPIC, server.baseUrl(), "claude-x", 0, null, false);
            assertEquals(List.of("claude-x"), ModelLister.list(c, "k", HttpStreaming.defaultClient()));
            assertEquals("/v1/models", server.lastPath);
            assertEquals("k", server.lastHeaders.getFirst("x-api-key"));
        }
    }

    @Test
    void httpErrorBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(401, "{\"error\":\"bad key\"}");
            ProviderConfig c = new ProviderConfig("p", "G", ProviderType.OPENAI_COMPATIBLE, server.baseUrl(), "m", 0, null, false);
            LlmException e = assertThrows(LlmException.class, () -> ModelLister.list(c, "k", HttpStreaming.defaultClient()));
            assertEquals(401, e.status());
        }
    }
}
