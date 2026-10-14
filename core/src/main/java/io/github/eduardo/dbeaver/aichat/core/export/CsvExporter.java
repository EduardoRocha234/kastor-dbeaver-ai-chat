package io.github.eduardo.dbeaver.aichat.core.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** RFC 4180 CSV in UTF-8 with a BOM, so Excel opens accented text correctly. */
public final class CsvExporter {

    private CsvExporter() {
    }

    public static void write(DataTable table, OutputStream out) throws IOException {
        Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        w.write('﻿');
        writeRow(w, table.columns());
        for (List<Object> row : table.rows()) {
            writeRow(w, row);
        }
        w.flush();
    }

    private static void writeRow(Writer w, List<?> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                w.write(',');
            }
            w.write(quote(Cells.text(values.get(i))));
        }
        w.write("\r\n");
    }

    static String quote(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
