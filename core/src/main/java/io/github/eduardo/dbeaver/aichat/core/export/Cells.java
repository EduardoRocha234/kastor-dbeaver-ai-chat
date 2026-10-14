package io.github.eduardo.dbeaver.aichat.core.export;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Text form of a typed cell, for formats without native types (CSV, PDF, DOCX). */
public final class Cells {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Cells() {
    }

    /** SQL NULL becomes the empty string. */
    public static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (value instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            return Long.toString(d.longValue());
        }
        if (value instanceof LocalDateTime t) {
            return DATE_TIME.format(t);
        }
        if (value instanceof LocalDate d) {
            return d.toString();
        }
        return value.toString();
    }

    public static boolean isNumeric(Object value) {
        return value instanceof Number;
    }
}
