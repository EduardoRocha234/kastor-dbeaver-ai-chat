package io.github.eduardo.dbeaver.aichat.core.export;

import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

/** One worksheet per table: bold frozen header with a filter, numbers and dates kept as Excel values. */
public final class XlsxExporter {

    /** Excel's limit is 1,048,576 rows including the header. */
    public static final int MAX_ROWS = 1_048_575;
    static final int MAX_SHEET_NAME = 31;
    private static final double MAX_WIDTH = 60;

    private XlsxExporter() {
    }

    public static void write(List<DataTable> tables, OutputStream out) throws IOException {
        Workbook workbook = new Workbook(out, "Kastor", "1.0");
        Set<String> used = new HashSet<>();
        for (DataTable table : tables) {
            writeSheet(workbook.newWorksheet(sheetName(table.name(), used)), table);
        }
        workbook.finish();
    }

    private static void writeSheet(Worksheet ws, DataTable table) throws IOException {
        int cols = table.columns().size();
        double[] widths = new double[cols];
        for (int c = 0; c < cols; c++) {
            String name = table.columns().get(c);
            ws.value(0, c, name);
            widths[c] = width(name);
        }
        if (cols > 0) {
            ws.range(0, 0, 0, cols - 1).style().bold().fillColor("DDE4EE").set();
            ws.freezePane(0, 1);
        }
        int r = 1;
        for (List<Object> row : table.rows()) {
            if (r > MAX_ROWS) {
                break;
            }
            for (int c = 0; c < cols && c < row.size(); c++) {
                Object v = row.get(c);
                if (v == null) {
                    continue;
                }
                if (v instanceof Number n) {
                    ws.value(r, c, n);
                } else if (v instanceof Boolean b) {
                    ws.value(r, c, b);
                } else if (v instanceof LocalDateTime t) {
                    ws.value(r, c, t);
                    ws.style(r, c).format("yyyy-mm-dd hh:mm:ss").set();
                } else if (v instanceof LocalDate d) {
                    ws.value(r, c, d);
                    ws.style(r, c).format("yyyy-mm-dd").set();
                } else {
                    ws.value(r, c, v.toString());
                }
                widths[c] = Math.max(widths[c], width(Cells.text(v)));
            }
            if (r % 10_000 == 0) {
                ws.flush();
            }
            r++;
        }
        if (cols > 0) {
            ws.setAutoFilter(0, 0, cols - 1);
        }
        for (int c = 0; c < cols; c++) {
            ws.width(c, widths[c]);
        }
        ws.finish();
    }

    private static double width(String text) {
        return Math.min(MAX_WIDTH, Math.max(8, text.length() + 2));
    }

    /** Excel forbids []:*?/\ in sheet names, limits them to 31 characters and compares them ignoring case. */
    static String sheetName(String requested, Set<String> used) {
        String base = requested == null ? "" : requested.replaceAll("[\\[\\]:*?/\\\\]", "_").replaceAll("^'+|'+$", "").trim();
        if (base.isEmpty()) {
            base = "Dados";
        }
        if (base.length() > MAX_SHEET_NAME) {
            base = base.substring(0, MAX_SHEET_NAME);
        }
        String name = base;
        for (int n = 2; used.contains(name.toLowerCase(Locale.ROOT)); n++) {
            String suffix = " (" + n + ")";
            name = base.substring(0, Math.min(base.length(), MAX_SHEET_NAME - suffix.length())) + suffix;
        }
        used.add(name.toLowerCase(Locale.ROOT));
        return name;
    }
}
