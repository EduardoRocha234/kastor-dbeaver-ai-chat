package io.github.eduardo.dbeaver.aichat.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative read/write classification. Anything that cannot be proven to be a single
 * read-only statement is WRITE.
 */
public final class SqlClassifier {

    public enum SqlKind {
        READ, WRITE
    }

    private static final Set<String> READ_STARTERS =
            Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE");
    private static final Set<String> FORBIDDEN_IN_READ = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "INTO");

    private SqlClassifier() {
    }

    public static SqlKind classify(String sql) {
        List<List<String>> statements;
        try {
            statements = tokenize(sql == null ? "" : sql);
        } catch (IllegalArgumentException e) {
            return SqlKind.WRITE;
        }
        if (statements.size() != 1) {
            return SqlKind.WRITE;
        }
        List<String> words = statements.get(0);
        String first = words.get(0);
        if (!READ_STARTERS.contains(first)) {
            return SqlKind.WRITE;
        }
        if ("EXPLAIN".equals(first) && words.contains("ANALYZE")) {
            return SqlKind.WRITE;
        }
        for (String word : words) {
            if (FORBIDDEN_IN_READ.contains(word)) {
                return SqlKind.WRITE;
            }
        }
        return SqlKind.READ;
    }

    /** Number of non-empty statements, or -1 when the text cannot be tokenized. */
    public static int statementCount(String sql) {
        try {
            return tokenize(sql == null ? "" : sql).size();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Splits into statements, each a list of upper-cased bare words (keywords/identifiers). */
    static List<List<String>> tokenize(String sql) {
        List<List<String>> statements = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                if (i + 2 < n && sql.charAt(i + 2) == '!') {
                    throw new IllegalArgumentException("executable comment");
                }
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated comment");
                }
                i = end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated bracket");
                }
                i = end + 1;
            } else if (c == '$' && dollarTag(sql, i) != null) {
                String tag = dollarTag(sql, i);
                int end = sql.indexOf(tag, i + tag.length());
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated dollar quote");
                }
                i = end + tag.length();
            } else if (c == ';') {
                if (!current.isEmpty()) {
                    statements.add(current);
                    current = new ArrayList<>();
                }
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                current.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                i++;
            }
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        return statements;
    }

    /** Returns the index after the closing quote; doubled quotes are escapes. Backslash is not. */
    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("unterminated quote");
    }

    /** "$$" or "$tag$" starting at i, or null (e.g. "$1" positional parameter). */
    private static String dollarTag(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        if (j < sql.length() && sql.charAt(j) == '$') {
            String tag = sql.substring(i, j + 1);
            if (tag.length() > 2 && Character.isDigit(tag.charAt(1))) {
                return null;
            }
            return tag;
        }
        return null;
    }
}
