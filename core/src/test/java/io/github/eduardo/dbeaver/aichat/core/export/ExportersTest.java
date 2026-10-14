package io.github.eduardo.dbeaver.aichat.core.export;

import static org.junit.jupiter.api.Assertions.*;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Heading;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.ListBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Paragraph;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.TableBlock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class ExportersTest {

    private static final DataTable SALES = new DataTable("vendas", List.of("mês", "total", "data", "obs"),
            List.of(Arrays.asList("janeiro", new BigDecimal("1234.50"), LocalDate.of(2026, 1, 31), "a, \"b\"\nc"),
                    Arrays.asList("fevereiro", 99, LocalDateTime.of(2026, 2, 1, 8, 30), null)),
            false);

    @Test
    void csvQuotesAndKeepsTypesAsText() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CsvExporter.write(SALES, out);
        assertEquals("\uFEFFmês,total,data,obs\r\n"
                + "janeiro,1234.50,2026-01-31,\"a, \"\"b\"\"\nc\"\r\n"
                + "fevereiro,99,2026-02-01 08:30:00,\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void xlsxWritesNumbersAsNumbersAndOneSheetPerTable() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XlsxExporter.write(List.of(SALES, new DataTable("vendas", List.of("x"), List.of(), false)), out);
        Map<String, String> entries = unzip(out.toByteArray());
        String sheet = entries.get("xl/worksheets/sheet1.xml");
        assertNotNull(sheet, entries.keySet().toString());
        assertTrue(sheet.contains("<v>1234.50</v>") || sheet.contains("<v>1234.5</v>"), sheet);
        assertTrue(entries.get("xl/workbook.xml").contains("vendas (2)"), entries.get("xl/workbook.xml"));
        assertTrue(sheet.contains("autoFilter"), sheet);
    }

    @Test
    void sheetNamesAreValidAndUnique() {
        HashSet<String> used = new HashSet<>();
        assertEquals("a_b_c", XlsxExporter.sheetName("a/b:c", used));
        assertEquals("A_B_C (2)", XlsxExporter.sheetName("A/B:C", used));
        String longName = XlsxExporter.sheetName("x".repeat(40), used);
        assertEquals(31, longName.length());
        assertEquals("Dados", XlsxExporter.sheetName("  ", used));
    }

    @Test
    void docxIsWellFormedWithStylesAndTables() throws Exception {
        ExportDocument doc = MarkdownToDocument.convert("Relatório <Q1>",
                "# Vendas\n\nTexto **forte** e *itálico* \u0001.\n\n- um\n- dois\n\n{{table:vendas}}", List.of(SALES),
                5000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DocxExporter.write(doc, out);
        Map<String, String> entries = unzip(out.toByteArray());
        assertEquals(new HashSet<>(List.of("[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels",
                "word/styles.xml", "word/document.xml")), entries.keySet());
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        for (Map.Entry<String, String> e : entries.entrySet()) {
            f.newDocumentBuilder().parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        String body = entries.get("word/document.xml");
        assertTrue(body.contains("Relatório &lt;Q1&gt;"));
        assertTrue(body.contains("w:val=\"Heading1\""));
        assertTrue(body.contains("<w:tbl>"));
        assertTrue(body.contains("1234.50"));
        assertFalse(body.contains("\u0001"));
    }

    @Test
    void pdfContainsTitleAndCells() throws Exception {
        ExportDocument doc = MarkdownToDocument.convert("Relatório de vendas", "Resumo.\n\n{{table:vendas}}",
                List.of(SALES), 5000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfExporter.write(doc, out);
        byte[] bytes = out.toByteArray();
        assertEquals("%PDF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        String text = new PdfTextExtractor(new PdfReader(bytes)).getTextFromPage(1);
        assertTrue(text.contains("Relatório de vendas"), text);
        assertTrue(text.contains("fevereiro"), text);
    }

    @Test
    void markdownBecomesBlocksAndAppendsUnusedDatasets() {
        DataTable other = new DataTable("outros", List.of("x"), List.of(List.of(1)), true);
        ExportDocument doc = MarkdownToDocument.convert("T", "## Título\n\n1. a\n2. b\n\n{{ table : vendas }}\n\n"
                + "| c1 | c2 |\n|---|---|\n| 1 | 2 |", List.of(SALES, other), 1);
        List<ExportDocument.Block> b = doc.blocks();
        assertEquals(new Heading(2, "Título"), b.get(0));
        assertTrue(((ListBlock) b.get(1)).ordered());
        TableBlock sales = (TableBlock) b.get(2);
        assertEquals(1, sales.rows().size());
        assertTrue(sales.note().contains("primeiras 1 de 2"), sales.note());
        TableBlock md = (TableBlock) b.get(3);
        assertEquals(List.of("c1", "c2"), md.columns());
        assertEquals(new Heading(2, "outros"), b.get(4));
        assertTrue(((TableBlock) b.get(5)).note().contains("limite"));
    }

    @Test
    void inlineStyles() {
        ExportDocument doc = MarkdownToDocument.convert(null, "a **b** *c* `d`", List.of(), 10);
        Paragraph p = (Paragraph) doc.blocks().get(0);
        assertEquals(List.of(new ExportDocument.Run("a ", false, false, false),
                new ExportDocument.Run("b", true, false, false), new ExportDocument.Run(" ", false, false, false),
                new ExportDocument.Run("c", false, true, false), new ExportDocument.Run(" ", false, false, false),
                new ExportDocument.Run("d", false, false, true)), p.runs());
    }

    @Test
    void sanitizesFileNames() {
        assertEquals("passwd", ExportFiles.sanitize("../../etc/passwd", ExportFormat.CSV));
        assertEquals("a_b", ExportFiles.sanitize("a:b.csv", ExportFormat.CSV));
        assertEquals("export", ExportFiles.sanitize(" .. ", ExportFormat.PDF));
        assertEquals("vendas 2026", ExportFiles.sanitize("C:\\tmp\\vendas 2026.xlsx", ExportFormat.XLSX));
        assertEquals(ExportFiles.MAX_NAME, ExportFiles.sanitize("x".repeat(300), ExportFormat.PDF).length());
    }

    /** Through ZipFile: fastexcel streams entries with data descriptors that ZipInputStream rejects. */
    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> entries = new HashMap<>();
        Path tmp = Files.createTempFile("export", ".zip");
        try {
            Files.write(tmp, bytes);
            try (ZipFile zip = new ZipFile(tmp.toFile())) {
                for (ZipEntry e : Collections.list(zip.entries())) {
                    entries.put(e.getName(), new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } finally {
            Files.delete(tmp);
        }
        return entries;
    }
}
