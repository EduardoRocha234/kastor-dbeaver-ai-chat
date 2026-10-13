package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

/** Compact pipe-separated rendering of query results for the model. */
public final class TextTable {

    static final int MAX_CELL = 200;

    private TextTable() {
    }

    public static String format(QueryResult result, int maxChars) {
        StringBuilder sb = new StringBuilder();
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < result.columns().size(); i++) {
            if (i > 0) {
                header.append(" | ");
            }
            header.append(cell(result.columns().get(i)));
        }
        sb.append(header).append('\n');
        sb.append("-".repeat(Math.max(3, Math.min(80, header.length())))).append('\n');
        int shown = 0;
        for (List<String> row : result.rows()) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    line.append(" | ");
                }
                line.append(cell(row.get(i)));
            }
            if (sb.length() + line.length() + 1 > maxChars) {
                sb.append("... saída truncada em ").append(maxChars).append(" caracteres; ")
                        .append(result.rows().size() - shown).append(" linha(s) não exibida(s)\n");
                return sb.toString();
            }
            sb.append(line).append('\n');
            shown++;
        }
        return sb.toString();
    }

    static String cell(String value) {
        if (value == null) {
            return "NULL";
        }
        String s = value.replace("\r", "").replace("\n", "\\n").replace("|", "\\|");
        return s.length() > MAX_CELL ? s.substring(0, MAX_CELL) + "…" : s;
    }
}
