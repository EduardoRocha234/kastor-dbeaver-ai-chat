package io.github.eduardo.dbeaver.aichat.core.export;

import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Block;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.CodeBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Heading;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.ListBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Paragraph;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Run;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.TableBlock;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Minimal WordprocessingML written by hand: styles for title, headings, code and a bordered table. */
public final class DocxExporter {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** A4 in twentieths of a point, with 2 cm margins. */
    private static final int PAGE_W = 11906;
    private static final int PAGE_H = 16838;
    private static final int MARGIN = 1134;

    private DocxExporter() {
    }

    public static void write(ExportDocument doc, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        entry(zip, "[Content_Types].xml", CONTENT_TYPES);
        entry(zip, "_rels/.rels", ROOT_RELS);
        entry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
        entry(zip, "word/styles.xml", STYLES);
        entry(zip, "word/document.xml", document(doc));
        zip.finish();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static String document(ExportDocument doc) {
        boolean landscape = Layout.landscape(doc);
        int textWidth = (landscape ? PAGE_H : PAGE_W) - 2 * MARGIN;
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:document xmlns:w=\"").append(W).append("\"><w:body>");
        if (doc.title() != null && !doc.title().isBlank()) {
            paragraph(sb, "Title", List.of(Run.plain(doc.title())), null);
        }
        for (Block block : doc.blocks()) {
            if (block instanceof Heading h) {
                paragraph(sb, "Heading" + Math.max(1, Math.min(3, h.level())), List.of(Run.plain(h.text())), null);
            } else if (block instanceof Paragraph p) {
                paragraph(sb, null, p.runs(), null);
            } else if (block instanceof ListBlock l) {
                for (int i = 0; i < l.items().size(); i++) {
                    String marker = l.ordered() ? (i + 1) + ".\t" : "\u2022\t";
                    paragraph(sb, "ListItem", l.items().get(i), marker);
                }
            } else if (block instanceof CodeBlock c) {
                code(sb, c.text());
            } else if (block instanceof TableBlock t) {
                table(sb, t, textWidth);
            }
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"").append(landscape ? PAGE_H : PAGE_W)
                .append("\" w:h=\"").append(landscape ? PAGE_W : PAGE_H).append('"')
                .append(landscape ? " w:orient=\"landscape\"" : "").append("/>")
                .append("<w:pgMar w:top=\"").append(MARGIN).append("\" w:right=\"").append(MARGIN)
                .append("\" w:bottom=\"").append(MARGIN).append("\" w:left=\"").append(MARGIN)
                .append("\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>")
                .append("</w:body></w:document>");
        return sb.toString();
    }

    private static void paragraph(StringBuilder sb, String style, List<Run> runs, String marker) {
        sb.append("<w:p>");
        if (style != null) {
            sb.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
        }
        if (marker != null) {
            run(sb, Run.plain(marker));
        }
        for (Run r : runs) {
            run(sb, r);
        }
        sb.append("</w:p>");
    }

    private static void run(StringBuilder sb, Run r) {
        sb.append("<w:r>");
        if (r.bold() || r.italic() || r.code()) {
            sb.append("<w:rPr>");
            if (r.code()) {
                sb.append("<w:rStyle w:val=\"CodeChar\"/>");
            }
            if (r.bold()) {
                sb.append("<w:b/>");
            }
            if (r.italic()) {
                sb.append("<w:i/>");
            }
            sb.append("</w:rPr>");
        }
        text(sb, r.text());
        sb.append("</w:r>");
    }

    /** Tabs and line breaks become their own elements; everything else is escaped text. */
    private static void text(StringBuilder sb, String text) {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\t') {
                flushText(sb, chunk);
                sb.append(c == '\n' ? "<w:br/>" : "<w:tab/>");
            } else if (c != '\r') {
                chunk.append(c);
            }
        }
        flushText(sb, chunk);
    }

    private static void flushText(StringBuilder sb, StringBuilder chunk) {
        if (chunk.length() > 0) {
            sb.append("<w:t xml:space=\"preserve\">").append(Xml.escape(chunk.toString())).append("</w:t>");
            chunk.setLength(0);
        }
    }

    private static void code(StringBuilder sb, String text) {
        sb.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/></w:pPr><w:r>");
        text(sb, text.endsWith("\n") ? text.substring(0, text.length() - 1) : text);
        sb.append("</w:r></w:p>");
    }

    private static void table(StringBuilder sb, TableBlock t, int textWidth) {
        int cols = Math.max(1, t.columns().size());
        int colWidth = textWidth / cols;
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/>")
                .append("</w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) {
            sb.append("<w:gridCol w:w=\"").append(colWidth).append("\"/>");
        }
        sb.append("</w:tblGrid><w:tr><w:trPr><w:tblHeader/></w:trPr>");
        for (String name : t.columns()) {
            sb.append("<w:tc><w:tcPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"DDE4EE\"/></w:tcPr><w:p>");
            run(sb, new Run(name, true, false, false));
            sb.append("</w:p></w:tc>");
        }
        sb.append("</w:tr>");
        for (List<Object> row : t.rows()) {
            sb.append("<w:tr>");
            for (int c = 0; c < t.columns().size(); c++) {
                Object v = c < row.size() ? row.get(c) : null;
                sb.append("<w:tc><w:p>");
                if (Cells.isNumeric(v)) {
                    sb.append("<w:pPr><w:jc w:val=\"right\"/></w:pPr>");
                }
                String text = Cells.text(v);
                if (!text.isEmpty()) {
                    run(sb, Run.plain(text));
                }
                sb.append("</w:p></w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
        // Word needs a paragraph between a table and whatever follows (including the end of the body).
        paragraph(sb, "TableNote", t.note() == null ? List.of() : List.of(new Run(t.note(), false, true, false)),
                null);
    }

    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
            <Default Extension="xml" ContentType="application/xml"/>\
            <Override PartName="/word/document.xml" \
            ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
            <Override PartName="/word/styles.xml" \
            ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>\
            </Types>""";

    private static final String ROOT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
            <Relationship Id="rId1" \
            Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
            Target="word/document.xml"/>\
            </Relationships>""";

    private static final String DOCUMENT_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
            <Relationship Id="rId1" \
            Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" \
            Target="styles.xml"/>\
            </Relationships>""";

    private static final String STYLES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
            <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">\
            <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/>\
            <w:sz w:val="22"/><w:lang w:val="pt-BR"/></w:rPr></w:rPrDefault>\
            <w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault>\
            </w:docDefaults>\
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>\
            <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>\
            <w:next w:val="Normal"/><w:pPr><w:spacing w:after="240"/></w:pPr>\
            <w:rPr><w:b/><w:color w:val="1F3864"/><w:sz w:val="40"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>\
            <w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/>\
            <w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3864"/><w:sz w:val="32"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>\
            <w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="100"/>\
            <w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="2F5496"/><w:sz w:val="28"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/>\
            <w:next w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="160" w:after="80"/>\
            <w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="24"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="ListItem"><w:name w:val="List Item"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:after="40"/><w:ind w:left="720" w:hanging="360"/></w:pPr></w:style>\
            <w:style w:type="paragraph" w:styleId="Code"><w:name w:val="Code"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:shd w:val="clear" w:color="auto" w:fill="F2F2F2"/><w:spacing w:after="120" w:line="240"/></w:pPr>\
            <w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/><w:sz w:val="19"/></w:rPr></w:style>\
            <w:style w:type="paragraph" w:styleId="TableNote"><w:name w:val="Table Note"/><w:basedOn w:val="Normal"/>\
            <w:pPr><w:spacing w:before="60" w:after="200"/></w:pPr><w:rPr><w:color w:val="595959"/><w:sz w:val="18"/>\
            </w:rPr></w:style>\
            <w:style w:type="character" w:styleId="CodeChar"><w:name w:val="Code Char"/>\
            <w:rPr><w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/></w:rPr></w:style>\
            <w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/>\
            <w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="18"/></w:rPr>\
            <w:tblPr><w:tblBorders>\
            <w:top w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>\
            <w:left w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>\
            <w:bottom w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>\
            <w:right w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>\
            <w:insideH w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>\
            <w:insideV w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/>\
            </w:tblBorders><w:tblCellMar><w:left w:w="80" w:type="dxa"/><w:right w:w="80" w:type="dxa"/>\
            </w:tblCellMar></w:tblPr></w:style>\
            </w:styles>""";
}
