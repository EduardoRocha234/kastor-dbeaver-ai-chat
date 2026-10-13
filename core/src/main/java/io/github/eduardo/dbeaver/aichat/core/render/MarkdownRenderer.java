package io.github.eduardo.dbeaver.aichat.core.render;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Markdown to HTML for the chat view. Raw HTML is escaped, unsafe URLs are dropped and images
 * become plain text, so rendered content never makes the browser fetch anything.
 */
public final class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
    }

    public String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        Node document = parser.parse(markdown);
        document.accept(new ImageRemover());
        return renderer.render(document);
    }

    /** Replaces each image with its alt text followed by the URL as text. */
    private static final class ImageRemover extends AbstractVisitor {
        @Override
        public void visit(Image image) {
            StringBuilder alt = new StringBuilder();
            for (Node child = image.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Text) {
                    alt.append(((Text) child).getLiteral());
                }
            }
            image.insertBefore(new Text("[imagem: " + alt + " — " + image.getDestination() + "]"));
            image.unlink();
        }
    }
}
