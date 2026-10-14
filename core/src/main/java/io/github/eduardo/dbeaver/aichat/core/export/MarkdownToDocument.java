package io.github.eduardo.dbeaver.aichat.core.export;

import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Block;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.CodeBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.ListBlock;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.Run;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument.TableBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * Turns the model's Markdown report into an {@link ExportDocument}. A paragraph holding only {@code {{table:name}}}
 * becomes that dataset's table; datasets the body never mentions are appended at the end, each under its name.
 */
public final class MarkdownToDocument {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*table\\s*:\\s*([^}]+?)\\s*\\}\\}");
    private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

    private final Map<String, DataTable> datasets = new LinkedHashMap<>();
    private final int maxTableRows;
    private final Set<String> used = new LinkedHashSet<>();

    private MarkdownToDocument(List<DataTable> tables, int maxTableRows) {
        for (DataTable t : tables) {
            datasets.put(t.name(), t);
        }
        this.maxTableRows = maxTableRows;
    }

    /**
     * body null or blank = only the datasets. maxTableRows caps every table (PDF/DOCX with 100k rows are
     * useless). Throws IllegalArgumentException naming an unknown placeholder.
     */
    public static ExportDocument convert(String title, String body, List<DataTable> tables, int maxTableRows) {
        MarkdownToDocument c = new MarkdownToDocument(tables, maxTableRows);
        List<Block> blocks = new ArrayList<>();
        if (body != null && !body.isBlank()) {
            for (Node n = PARSER.parse(body).getFirstChild(); n != null; n = n.getNext()) {
                c.block(n, blocks);
            }
        }
        boolean single = tables.size() == 1 && blocks.isEmpty();
        for (DataTable t : tables) {
            if (!c.used.contains(t.name())) {
                if (!single) {
                    blocks.add(new ExportDocument.Heading(2, t.name()));
                }
                blocks.add(c.table(t));
            }
        }
        return new ExportDocument(title, blocks);
    }

    private void block(Node n, List<Block> out) {
        if (n instanceof Heading h) {
            out.add(new ExportDocument.Heading(h.getLevel(), plain(h)));
        } else if (n instanceof Paragraph p) {
            Matcher m = PLACEHOLDER.matcher(plain(p).trim());
            if (m.matches()) {
                String name = m.group(1);
                DataTable t = datasets.get(name);
                if (t == null) {
                    throw new IllegalArgumentException("O body referencia {{table:" + name
                            + "}}, mas não há dataset com esse nome. Datasets: " + String.join(", ", datasets.keySet()));
                }
                used.add(name);
                out.add(table(t));
            } else {
                out.add(new ExportDocument.Paragraph(runs(p)));
            }
        } else if (n instanceof BulletList || n instanceof OrderedList) {
            List<List<Run>> items = new ArrayList<>();
            for (Node item = n.getFirstChild(); item != null; item = item.getNext()) {
                if (item instanceof ListItem) {
                    items.add(listItemRuns(item));
                }
            }
            out.add(new ListBlock(n instanceof OrderedList, items));
        } else if (n instanceof FencedCodeBlock f) {
            out.add(new CodeBlock(f.getLiteral()));
        } else if (n instanceof IndentedCodeBlock i) {
            out.add(new CodeBlock(i.getLiteral()));
        } else if (n instanceof BlockQuote) {
            for (Node child = n.getFirstChild(); child != null; child = child.getNext()) {
                List<Block> inner = new ArrayList<>();
                block(child, inner);
                for (Block b : inner) {
                    out.add(b instanceof ExportDocument.Paragraph para ? italic(para) : b);
                }
            }
        } else if (n instanceof org.commonmark.ext.gfm.tables.TableBlock) {
            out.add(markdownTable(n));
        } else if (n instanceof HtmlBlock html) {
            out.add(new ExportDocument.Paragraph(List.of(Run.plain(html.getLiteral().trim()))));
        }
        // ThematicBreak and anything unknown: dropped.
    }

    private TableBlock table(DataTable t) {
        List<List<Object>> rows = t.rows();
        String note = null;
        if (rows.size() > maxTableRows) {
            note = "Mostrando as primeiras " + maxTableRows + " de " + rows.size()
                    + " linhas. Gere uma planilha (xlsx/csv) para os dados completos.";
            rows = rows.subList(0, maxTableRows);
        } else if (t.truncated()) {
            note = rows.size() + " linhas (limite de exportação atingido; a consulta tem mais).";
        }
        return new TableBlock(t.columns(), rows, note);
    }

    private static TableBlock markdownTable(Node table) {
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
            for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) {
                    continue;
                }
                List<Object> cells = new ArrayList<>();
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (cell instanceof TableCell) {
                        cells.add(plain(cell));
                    }
                }
                if (section instanceof TableHead && columns.isEmpty()) {
                    cells.forEach(c -> columns.add((String) c));
                } else {
                    rows.add(cells);
                }
            }
        }
        return new TableBlock(columns, rows, null);
    }

    private static List<Run> listItemRuns(Node item) {
        List<Run> runs = new ArrayList<>();
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (!runs.isEmpty()) {
                runs.add(Run.plain("\n"));
            }
            if (child instanceof Paragraph) {
                runs.addAll(runs(child));
            } else {
                // nested list or code inside an item: keep its text, flattened
                runs.add(Run.plain(plain(child)));
            }
        }
        return runs;
    }

    private static ExportDocument.Paragraph italic(ExportDocument.Paragraph p) {
        return new ExportDocument.Paragraph(p.runs().stream()
                .map(r -> new Run(r.text(), r.bold(), true, r.code())).toList());
    }

    static List<Run> runs(Node parent) {
        List<Run> out = new ArrayList<>();
        inline(parent, false, false, out);
        return out;
    }

    private static void inline(Node parent, boolean bold, boolean italic, List<Run> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof Text t) {
                out.add(new Run(t.getLiteral(), bold, italic, false));
            } else if (n instanceof Code c) {
                out.add(new Run(c.getLiteral(), bold, italic, true));
            } else if (n instanceof StrongEmphasis) {
                inline(n, true, italic, out);
            } else if (n instanceof Emphasis) {
                inline(n, bold, true, out);
            } else if (n instanceof SoftLineBreak) {
                out.add(new Run(" ", bold, italic, false));
            } else if (n instanceof HardLineBreak) {
                out.add(new Run("\n", bold, italic, false));
            } else if (n instanceof HtmlInline h) {
                out.add(new Run(h.getLiteral(), bold, italic, false));
            } else {
                // links, images and anything else: keep the visible text
                inline(n, bold, italic, out);
            }
        }
    }

    static String plain(Node parent) {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs(parent)) {
            sb.append(r.text());
        }
        if (sb.length() == 0 && parent instanceof FencedCodeBlock f) {
            return f.getLiteral();
        }
        return sb.toString();
    }
}
