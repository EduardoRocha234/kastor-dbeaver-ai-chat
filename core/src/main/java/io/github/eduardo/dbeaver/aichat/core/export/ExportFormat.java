package io.github.eduardo.dbeaver.aichat.core.export;

import java.util.Locale;

public enum ExportFormat {
    CSV, XLSX, PDF, DOCX;

    public String extension() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** null when the text names no supported format. */
    public static ExportFormat parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.startsWith(".")) {
            t = t.substring(1);
        }
        return switch (t) {
            case "csv" -> CSV;
            case "xlsx", "excel", "planilha", "spreadsheet" -> XLSX;
            case "pdf" -> PDF;
            case "docx", "word" -> DOCX;
            default -> null;
        };
    }
}
