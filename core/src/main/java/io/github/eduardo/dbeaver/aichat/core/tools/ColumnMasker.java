package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Hides cell values of columns whose name matches a pattern before results go to the model.
 * Patterns are case-insensitive column names where * and ? are wildcards. Matching is by the column label of
 * the result, so a query that aliases or wraps a column (select cpf as x) is not caught.
 */
public final class ColumnMasker {

    public static final String MASK = "***";
    public static final ColumnMasker NONE = new ColumnMasker(List.of());

    private final List<Pattern> patterns;

    private ColumnMasker(List<Pattern> patterns) {
        this.patterns = patterns;
    }

    /** Patterns separated by commas, semicolons or line breaks. */
    public static ColumnMasker parse(String text) {
        List<Pattern> list = new ArrayList<>();
        if (text != null) {
            for (String raw : text.split("[,;\\r\\n]+")) {
                String p = raw.trim();
                if (!p.isEmpty()) {
                    list.add(Pattern.compile(glob(p), Pattern.CASE_INSENSITIVE));
                }
            }
        }
        return list.isEmpty() ? NONE : new ColumnMasker(list);
    }

    private static String glob(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    public boolean matches(String column) {
        if (column == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(column).matches()) {
                return true;
            }
        }
        return false;
    }

    public List<String> maskedColumns(List<String> columns) {
        return columns.stream().filter(this::matches).toList();
    }

    /** SQL NULL stays NULL; every other value of a matching column becomes {@link #MASK}. */
    public QueryResult apply(QueryResult result) {
        if (patterns.isEmpty()) {
            return result;
        }
        boolean[] masked = new boolean[result.columns().size()];
        boolean any = false;
        for (int i = 0; i < masked.length; i++) {
            masked[i] = matches(result.columns().get(i));
            any |= masked[i];
        }
        if (!any) {
            return result;
        }
        List<List<String>> rows = new ArrayList<>(result.rows().size());
        for (List<String> row : result.rows()) {
            List<String> copy = new ArrayList<>(row);
            for (int i = 0; i < copy.size() && i < masked.length; i++) {
                if (masked[i] && copy.get(i) != null) {
                    copy.set(i, MASK);
                }
            }
            rows.add(copy);
        }
        return new QueryResult(result.columns(), rows, result.truncated());
    }

    /** Typed counterpart of {@link #apply(QueryResult)}: masked cells become the text {@link #MASK}. */
    public TypedResult apply(TypedResult result) {
        if (patterns.isEmpty()) {
            return result;
        }
        boolean[] masked = new boolean[result.columns().size()];
        boolean any = false;
        for (int i = 0; i < masked.length; i++) {
            masked[i] = matches(result.columns().get(i));
            any |= masked[i];
        }
        if (!any) {
            return result;
        }
        List<List<Object>> rows = new ArrayList<>(result.rows().size());
        for (List<Object> row : result.rows()) {
            List<Object> copy = new ArrayList<>(row);
            for (int i = 0; i < copy.size() && i < masked.length; i++) {
                if (masked[i] && copy.get(i) != null) {
                    copy.set(i, MASK);
                }
            }
            rows.add(copy);
        }
        return new TypedResult(result.columns(), rows, result.truncated());
    }
}
