package io.github.eduardo.dbeaver.aichat.core.mention;

import io.github.eduardo.dbeaver.aichat.core.tools.ContainerInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.TableInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "@" references to database objects: "@schema" or "@schema.table", where schema is a path from list_schemas
 * ("db.schema" on multi-database connections; "@db" alone names a database). Connections without schemas use "@table".
 */
public final class Mentions {

    /** The "@query" being typed: start = index of '@', text = what follows it up to the caret. */
    public record Query(int start, String text) {
    }

    private static final Pattern MENTION = Pattern.compile("(?<![^\\s])@([^\\s@]+)");
    private static final String TRAILING_PUNCTUATION = ".,;:!?)]}\"'";

    private Mentions() {
    }

    /** The mention under the caret, or null. '@' must start the text or follow whitespace. */
    public static Query queryAt(String text, int caret) {
        for (int i = Math.min(caret, text.length()) - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') {
                return i == 0 || Character.isWhitespace(text.charAt(i - 1)) ? new Query(i, text.substring(i + 1, caret)) : null;
            }
            if (Character.isWhitespace(c)) {
                return null;
            }
        }
        return null;
    }

    /**
     * Completions for a query, walking the object tree one level at a time: databases, then a database's schemas,
     * then a schema's tables. containers maps a path ("" = root) to its loaded children; tables maps a schema path
     * ("" = a connection without schemas) to its loaded tables. Only the level the query reaches has to be loaded.
     */
    public static Suggestions suggest(String query, Map<String, List<ContainerInfo>> containers,
            Map<String, List<TableInfo>> tables, int limit) {
        String parent = "";
        String rest = query;
        while (true) {
            List<ContainerInfo> children = containers.get(parent);
            if (children == null) {
                return new Suggestions(List.of(), parent, null);
            }
            if (children.isEmpty() && parent.isEmpty()) {
                return tablesOf("", rest, tables, limit);
            }
            ContainerInfo next = longestPrefix(rest, children);
            if (next == null) {
                break;
            }
            String path = join(parent, next.name());
            rest = rest.substring(next.name().length() + 1);
            if (!next.holdsSchemas()) {
                return tablesOf(path, rest, tables, limit);
            }
            parent = path;
        }
        String q = rest.toLowerCase(Locale.ROOT);
        List<Ranked> hits = new ArrayList<>();
        for (ContainerInfo c : containers.get(parent)) {
            int rank = rank(c.name(), q);
            if (rank >= 0) {
                String path = join(parent, c.name());
                hits.add(new Ranked(rank, new Suggestion(path, c.holdsSchemas() ? "database" : "schema", "@" + path + ".", true)));
            }
        }
        List<Suggestion> out = sorted(hits);
        if (parent.isEmpty()) {
            // At the top level, tables already loaded anywhere can be found by name.
            List<Ranked> tableHits = new ArrayList<>();
            for (Map.Entry<String, List<TableInfo>> e : tables.entrySet()) {
                for (TableInfo t : e.getValue()) {
                    int rank = rank(join(e.getKey(), t.name()), q);
                    if (rank >= 0) {
                        tableHits.add(new Ranked(rank, table(e.getKey(), t)));
                    }
                }
            }
            out.addAll(sorted(tableHits));
        }
        return Suggestions.of(out.subList(0, Math.min(limit, out.size())));
    }

    /** Paths of every loaded container that holds tables (what the tools accept as their schema parameter). */
    public static List<String> schemaPaths(Map<String, List<ContainerInfo>> containers) {
        List<String> out = new ArrayList<>();
        collect("", containers, out);
        return out;
    }

    private static void collect(String parent, Map<String, List<ContainerInfo>> containers, List<String> out) {
        for (ContainerInfo c : containers.getOrDefault(parent, List.of())) {
            String path = join(parent, c.name());
            if (c.holdsSchemas()) {
                collect(path, containers, out);
            } else {
                out.add(path);
            }
        }
    }

    private static String join(String parent, String name) {
        return parent.isEmpty() ? name : parent + "." + name;
    }

    /** The child whose name + "." starts the text (case-insensitive), preferring the longest name. */
    private static ContainerInfo longestPrefix(String text, List<ContainerInfo> children) {
        String lower = text.toLowerCase(Locale.ROOT);
        ContainerInfo best = null;
        for (ContainerInfo c : children) {
            if (lower.startsWith(c.name().toLowerCase(Locale.ROOT) + ".")
                    && (best == null || c.name().length() > best.name().length())) {
                best = c;
            }
        }
        return best;
    }

    /** Distinct mentions in a message, resolved against the connection's schema paths. */
    public static List<Mention> parse(String text, List<String> schemas) {
        Set<Mention> out = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            while (!token.isEmpty() && TRAILING_PUNCTUATION.indexOf(token.charAt(token.length() - 1)) >= 0) {
                token = token.substring(0, token.length() - 1);
            }
            if (token.isEmpty()) {
                continue;
            }
            out.add(resolve(token, schemas));
        }
        return new ArrayList<>(out);
    }

    private static Mention resolve(String token, List<String> schemas) {
        for (String s : schemas) {
            if (s.equalsIgnoreCase(token)) {
                return new Mention(s, null);
            }
        }
        String prefix = longestSchemaPrefix(token.toLowerCase(Locale.ROOT), schemas);
        return prefix == null ? new Mention(null, token) : new Mention(prefix, token.substring(prefix.length() + 1));
    }

    private record Ranked(int rank, Suggestion suggestion) {
    }

    private static Suggestions tablesOf(String schema, String filter, Map<String, List<TableInfo>> tablesBySchema,
            int limit) {
        List<TableInfo> tables = tablesBySchema.get(schema);
        if (tables == null) {
            return new Suggestions(List.of(), null, schema);
        }
        String q = filter.toLowerCase(Locale.ROOT);
        List<Ranked> hits = new ArrayList<>();
        for (TableInfo t : tables) {
            int rank = rank(t.name(), q);
            if (rank >= 0) {
                hits.add(new Ranked(rank, table(schema, t)));
            }
        }
        List<Suggestion> out = sorted(hits);
        return Suggestions.of(out.subList(0, Math.min(limit, out.size())));
    }

    private static Suggestion table(String schema, TableInfo t) {
        String path = join(schema, t.name());
        return new Suggestion(path, t.type(), "@" + path + " ", false);
    }

    /** 0 = last segment starts with q, 1 = whole path starts with q, 2 = contains q, -1 = no match. */
    private static int rank(String path, String q) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.substring(lower.lastIndexOf('.') + 1).startsWith(q)) {
            return 0;
        }
        if (lower.startsWith(q)) {
            return 1;
        }
        return lower.contains(q) ? 2 : -1;
    }

    private static List<Suggestion> sorted(List<Ranked> hits) {
        hits.sort(Comparator.comparingInt(Ranked::rank));
        List<Suggestion> out = new ArrayList<>(hits.size());
        hits.forEach(h -> out.add(h.suggestion()));
        return out;
    }

    /** The longest schema s such that lowerText starts with s + ".", in its original case. */
    private static String longestSchemaPrefix(String lowerText, List<String> schemas) {
        String best = null;
        for (String s : schemas) {
            if (lowerText.startsWith(s.toLowerCase(Locale.ROOT) + ".") && (best == null || s.length() > best.length())) {
                best = s;
            }
        }
        return best;
    }
}
