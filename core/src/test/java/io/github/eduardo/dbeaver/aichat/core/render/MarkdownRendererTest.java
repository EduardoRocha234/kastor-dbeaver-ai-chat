package io.github.eduardo.dbeaver.aichat.core.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void escapesRawHtml() {
        String html = renderer.toHtml("Oi <script>alert(1)</script> <img src=x onerror=alert(1)>");
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void sanitizesJavascriptLinks() {
        String html = renderer.toHtml("[clique](javascript:alert(1))");
        assertFalse(html.contains("javascript:"), html);
    }

    @Test
    void rendersSqlCodeBlocks() {
        String html = renderer.toHtml("```sql\nselect * from t where a < 1;\n```");
        assertTrue(html.contains("<pre><code class=\"language-sql\">select * from t where a &lt; 1;"), html);
    }

    @Test
    void rendersTables() {
        String html = renderer.toHtml("| a | b |\n|---|---|\n| 1 | 2 |\n");
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<td>2</td>"), html);
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", renderer.toHtml(null));
    }
}
