package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesEventsSeparatedByBlankLines() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream("event: a\ndata: {\"x\":1}\n\ndata: second\n\n"),
                (event, data) -> out.add(event + "|" + data), new CancellationToken());
        assertEquals(List.of("a|{\"x\":1}", "null|second"), out);
    }

    @Test
    void joinsMultiLineDataAndIgnoresComments() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream(": keep-alive\ndata:one\ndata: two\n\n"),
                (event, data) -> out.add(data), new CancellationToken());
        assertEquals(List.of("one\ntwo"), out);
    }

    @Test
    void dispatchesLastEventWithoutTrailingBlankLine() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream("data: last"), (event, data) -> out.add(data), new CancellationToken());
        assertEquals(List.of("last"), out);
    }

    @Test
    void handlesCrLf() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream("data: a\r\n\r\ndata: b\r\n\r\n"), (event, data) -> out.add(data), new CancellationToken());
        assertEquals(List.of("a", "b"), out);
    }

    @Test
    void stopsWhenCancelled() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertThrows(CancelledException.class,
                () -> SseParser.parse(stream("data: a\n\n"), (event, data) -> fail("must not dispatch"), token));
    }
}
