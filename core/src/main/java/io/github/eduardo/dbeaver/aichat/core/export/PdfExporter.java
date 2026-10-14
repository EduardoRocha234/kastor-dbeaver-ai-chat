package io.github.eduardo.dbeaver.aichat.core.export;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.ListItem;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Block;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.CodeBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Heading;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.ListBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Paragraph;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Run;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.TableBlock;
import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * PDF through OpenPDF with the standard Helvetica/Courier fonts (Windows-1252, which covers Portuguese; characters
 * outside it are not drawn). A4, landscape when a table is wide, header row repeated on every page, page numbers.
 */
public final class PdfExporter {

    private static final float MARGIN = 40;
    private static final Color HEADER_FILL = new Color(0xDD, 0xE4, 0xEE);
    private static final Color TITLE_COLOR = new Color(0x1F, 0x38, 0x64);
    private static final Color MUTED = new Color(0x59, 0x59, 0x59);
    private static final Color CODE_FILL = new Color(0xF2, 0xF2, 0xF2);

    private PdfExporter() {
    }

    public static void write(ExportDocument doc, OutputStream out) throws IOException {
        Rectangle size = Layout.landscape(doc) ? PageSize.A4.rotate() : PageSize.A4;
        Document pdf = new Document(size, MARGIN, MARGIN, MARGIN, MARGIN + 10);
        try {
            PdfWriter writer = PdfWriter.getInstance(pdf, out);
            writer.setPageEvent(new PageNumbers());
            if (doc.title() != null) {
                pdf.addTitle(doc.title());
            }
            pdf.addCreator("Kastor");
            pdf.open();
            if (doc.title() != null && !doc.title().isBlank()) {
                com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph(doc.title(),
                        new Font(Font.HELVETICA, 20, Font.BOLD, TITLE_COLOR));
                title.setSpacingAfter(12);
                pdf.add(title);
            }
            for (Block block : doc.blocks()) {
                add(pdf, block);
            }
            pdf.close();
        } catch (DocumentException e) {
            throw new IOException("Falha ao gerar PDF: " + e.getMessage(), e);
        }
    }

    private static void add(Document pdf, Block block) throws DocumentException {
        if (block instanceof Heading h) {
            float size = switch (Math.max(1, Math.min(3, h.level()))) {
                case 1 -> 16;
                case 2 -> 14;
                default -> 12;
            };
            com.lowagie.text.Paragraph p = new com.lowagie.text.Paragraph(h.text(),
                    new Font(Font.HELVETICA, size, Font.BOLD, TITLE_COLOR));
            p.setSpacingBefore(10);
            p.setSpacingAfter(6);
            p.setKeepTogether(true);
            pdf.add(p);
        } else if (block instanceof Paragraph p) {
            com.lowagie.text.Paragraph para = new com.lowagie.text.Paragraph(phrase(p.runs(), 10));
            para.setSpacingAfter(6);
            pdf.add(para);
        } else if (block instanceof ListBlock l) {
            com.lowagie.text.List list = new com.lowagie.text.List(l.ordered(), 14);
            if (!l.ordered()) {
                list.setListSymbol(new Chunk("•  ", font(10, Font.NORMAL)));
            }
            for (List<Run> item : l.items()) {
                list.add(new ListItem(phrase(item, 10)));
            }
            pdf.add(list);
            pdf.add(new com.lowagie.text.Paragraph(" ", font(4, Font.NORMAL)));
        } else if (block instanceof CodeBlock c) {
            PdfPTable box = new PdfPTable(1);
            box.setWidthPercentage(100);
            PdfPCell cell = new PdfPCell(new Phrase(c.text(), new Font(Font.COURIER, 8.5f)));
            cell.setBackgroundColor(CODE_FILL);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(6);
            box.addCell(cell);
            box.setSpacingAfter(8);
            pdf.add(box);
        } else if (block instanceof TableBlock t) {
            table(pdf, t);
        }
    }

    private static void table(Document pdf, TableBlock t) throws DocumentException {
        int cols = t.columns().size();
        if (cols == 0) {
            return;
        }
        float fontSize = cols > 10 ? 6.5f : cols > 6 ? 7.5f : 8.5f;
        PdfPTable table = new PdfPTable(cols);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSplitLate(false);
        table.setSpacingBefore(4);
        for (String name : t.columns()) {
            PdfPCell cell = cell(name, font(fontSize, Font.BOLD));
            cell.setBackgroundColor(HEADER_FILL);
            table.addCell(cell);
        }
        Font body = font(fontSize, Font.NORMAL);
        for (List<Object> row : t.rows()) {
            for (int c = 0; c < cols; c++) {
                Object v = c < row.size() ? row.get(c) : null;
                PdfPCell cell = cell(Cells.text(v), body);
                if (Cells.isNumeric(v)) {
                    cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                }
                table.addCell(cell);
            }
        }
        pdf.add(table);
        com.lowagie.text.Paragraph note = new com.lowagie.text.Paragraph(t.note() == null ? " " : t.note(),
                new Font(Font.HELVETICA, 8, Font.ITALIC, MUTED));
        note.setSpacingAfter(8);
        pdf.add(note);
    }

    private static PdfPCell cell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setPadding(3);
        cell.setBorderColor(new Color(0xA6, 0xA6, 0xA6));
        cell.setBorderWidth(0.5f);
        return cell;
    }

    private static Phrase phrase(List<Run> runs, float size) {
        Phrase phrase = new Phrase();
        for (Run r : runs) {
            int style = (r.bold() ? Font.BOLD : 0) | (r.italic() ? Font.ITALIC : 0);
            Font f = r.code() ? new Font(Font.COURIER, size, style) : font(size, style);
            phrase.add(new Chunk(r.text(), f));
        }
        return phrase;
    }

    private static Font font(float size, int style) {
        return new Font(Font.HELVETICA, size, style);
    }

    private static final class PageNumbers extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER,
                    new Phrase("Página " + writer.getPageNumber(), new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED)),
                    (page.getLeft() + page.getRight()) / 2, page.getBottom() + 20, 0);
        }
    }
}
