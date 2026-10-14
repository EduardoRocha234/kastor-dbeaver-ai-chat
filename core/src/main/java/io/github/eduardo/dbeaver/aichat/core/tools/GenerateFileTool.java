package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.export.CsvExporter;
import io.github.eduardo.dbeaver.aichat.core.export.DataTable;
import io.github.eduardo.dbeaver.aichat.core.export.DocxExporter;
import io.github.eduardo.dbeaver.aichat.core.export.ExportDocument;
import io.github.eduardo.dbeaver.aichat.core.export.ExportFiles;
import io.github.eduardo.dbeaver.aichat.core.export.ExportFormat;
import io.github.eduardo.dbeaver.aichat.core.export.MarkdownToDocument;
import io.github.eduardo.dbeaver.aichat.core.export.PdfExporter;
import io.github.eduardo.dbeaver.aichat.core.export.XlsxExporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs read-only queries and writes their full results to a file in the export folder. The data goes from the
 * database straight to the file: the model only gets the file name and row counts back.
 */
final class GenerateFileTool implements DbTool {

    static final int MAX_DATASETS = 20;
    /** Tables in PDF/DOCX beyond this are cut; a report with 100k rows is unreadable anyway. */
    static final int MAX_DOCUMENT_ROWS = 5_000;

    private final DatabaseGateway gateway;

    GenerateFileTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return GenerateFileResult.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Create a file for the user (xlsx spreadsheet, csv, pdf or docx report) from read-only queries. "
                + "Each dataset's SQL runs directly against the database and ALL its rows go into the file (up to the "
                + "export limit), without passing through you: you only get the file name and row counts back. "
                + "xlsx: one sheet per dataset. csv: exactly one dataset. pdf/docx: 'body' is a Markdown report "
                + "(headings, paragraphs, lists, bold) where a line containing only {{table:<dataset name>}} inserts "
                + "that dataset as a table; datasets not referenced are appended at the end. The user sees the "
                + "file in the chat with buttons to open or save it.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "format":{"type":"string","enum":["xlsx","csv","pdf","docx"]},
                  "filename":{"type":"string","description":"File name without extension or folder, e.g. \\"vendas-2026-por-mes\\"."},
                  "title":{"type":"string","description":"Document title (pdf/docx heading, file metadata)."},
                  "connection":{"type":"string","description":"Default connection for the datasets. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Default schema path for the datasets, as in run_query."},
                  "datasets":{"type":"array","minItems":1,"items":{"type":"object","properties":{
                    "name":{"type":"string","description":"Short unique name; xlsx sheet name and {{table:name}} key."},
                    "sql":{"type":"string","description":"A single read-only SQL statement. Test it first with run_query and a small LIMIT."},
                    "connection":{"type":"string"},
                    "schema":{"type":"string"}
                  },"required":["name","sql"],"additionalProperties":false}},
                  "body":{"type":"string","description":"pdf/docx only: Markdown report text with {{table:name}} placeholders."}
                },"required":["format","filename","datasets"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ExportSettings exports = ctx.exports();
        if (exports.directory() == null) {
            throw new ToolException("A geração de arquivos não está disponível nesta sessão.");
        }
        ExportFormat format = ExportFormat.parse(Args.requireString(args, "format"));
        if (format == null) {
            throw new ToolException("Formato inválido. Use xlsx, csv, pdf ou docx.");
        }
        String baseName = ExportFiles.sanitize(Args.requireString(args, "filename"), format);
        String title = Args.optString(args, "title");
        String body = Args.optString(args, "body");
        List<DatasetSpec> specs = datasets(args, ctx);
        if (format == ExportFormat.CSV && specs.size() != 1) {
            throw new ToolException("CSV aceita exatamente um dataset; para vários, use xlsx (uma aba por dataset).");
        }

        // Check every statement before running any, so a bad one in the middle doesn't waste the others.
        ToolPolicy policy = ctx.policy();
        for (DatasetSpec spec : specs) {
            if (!ReadSqlGuard.isRead(gateway, spec.connection().id(), spec.sql())) {
                policy.audit(name(), spec.connection(), spec.schema(), spec.sql(), "blocked", null, null,
                        "not confirmed read-only");
                throw new ToolException("O SQL do dataset '" + spec.name() + "' modifica dados ou não pôde ser "
                        + "confirmado como somente leitura. generate_file só aceita um único SELECT por dataset.");
            }
        }

        int maxRows = Math.min(exports.maxRows(), XlsxExporter.MAX_ROWS);
        List<DataTable> tables = new ArrayList<>();
        long totalRows = 0;
        for (DatasetSpec spec : specs) {
            ctx.cancel().throwIfCancelled();
            TypedResult raw;
            try {
                raw = gateway.runTypedReadQuery(spec.connection().id(), spec.schema(), spec.sql(), maxRows, ctx.cancel());
            } catch (GatewayException e) {
                policy.audit(name(), spec.connection(), spec.schema(), spec.sql(), "error", null, null, e.getMessage());
                throw new GatewayException("Dataset '" + spec.name() + "': " + e.getMessage(), e);
            }
            TypedResult result = policy.masker().apply(raw);
            policy.audit(name(), spec.connection(), spec.schema(), spec.sql(), "executed", (long) raw.rows().size(),
                    policy.masker().maskedColumns(raw.columns()), format.extension() + ": " + baseName);
            tables.add(new DataTable(spec.name(), result.columns(), result.rows(), result.truncated()));
            totalRows += result.rows().size();
        }

        ExportDocument document = null;
        if (format == ExportFormat.PDF || format == ExportFormat.DOCX) {
            try {
                document = MarkdownToDocument.convert(title, body, tables, MAX_DOCUMENT_ROWS);
            } catch (IllegalArgumentException e) {
                throw new ToolException(e.getMessage());
            }
        }

        ctx.cancel().throwIfCancelled();
        ExportDocument doc = document;
        Path path;
        try {
            path = ExportFiles.writeNew(exports.directory(), baseName, format, out -> {
                switch (format) {
                    case CSV -> CsvExporter.write(tables.get(0), out);
                    case XLSX -> XlsxExporter.write(tables, out);
                    case PDF -> PdfExporter.write(doc, out);
                    case DOCX -> DocxExporter.write(doc, out);
                }
            });
        } catch (IOException e) {
            throw new ToolException("Não foi possível gravar o arquivo em " + exports.directory() + ": " + e.getMessage());
        }

        long size = sizeOf(path);
        exports.onFile().fileGenerated(new GeneratedFile(path, format.extension(), size, totalRows));
        return ToolOutcome.ok(summary(path, format, tables, totalRows, size));
    }

    private List<DatasetSpec> datasets(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        JsonElement raw = args.get("datasets");
        if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
            throw new ToolException("Parâmetro obrigatório ausente: 'datasets' (lista com name e sql).");
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > MAX_DATASETS) {
            throw new ToolException("No máximo " + MAX_DATASETS + " datasets por arquivo.");
        }
        String defaultConnection = Args.optString(args, "connection");
        String defaultSchema = Args.optString(args, "schema");
        List<DatasetSpec> specs = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonElement e : array) {
            if (!e.isJsonObject()) {
                throw new ToolException("Cada item de 'datasets' deve ser um objeto com name e sql.");
            }
            JsonObject d = e.getAsJsonObject();
            String name = Args.requireString(d, "name").trim();
            if (!names.add(name)) {
                throw new ToolException("Nome de dataset repetido: '" + name + "'.");
            }
            String connection = Args.optString(d, "connection");
            ConnectionInfo conn = ConnectionResolver.resolve(gateway,
                    connection != null ? connection : defaultConnection, ctx.defaultConnectionId());
            String schema = Args.optString(d, "schema");
            specs.add(new DatasetSpec(name, Args.requireString(d, "sql"), conn, schema != null ? schema : defaultSchema));
        }
        return specs;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String summary(Path path, ExportFormat format, List<DataTable> tables, long totalRows, long size) {
        StringBuilder sb = new StringBuilder(GenerateFileResult.PREFIX).append(path.getFileName())
                .append(" (").append(totalRows).append(" linha(s)");
        if (format == ExportFormat.XLSX) {
            sb.append(", ").append(tables.size()).append(" aba(s)");
        }
        if (size >= 0) {
            sb.append(", ").append(Math.max(1, (size + 512) / 1024)).append(" KB");
        }
        sb.append(").");
        for (DataTable t : tables) {
            sb.append("\n- ").append(t.name()).append(": ").append(t.rows().size()).append(" linha(s), ")
                    .append(t.columns().size()).append(" coluna(s)");
            if (t.truncated()) {
                sb.append(" — LIMITE DE EXPORTAÇÃO ATINGIDO, a consulta tem mais linhas");
            }
            if ((format == ExportFormat.PDF || format == ExportFormat.DOCX) && t.rows().size() > MAX_DOCUMENT_ROWS) {
                sb.append(" — tabela do documento cortada em ").append(MAX_DOCUMENT_ROWS).append(" linhas");
            }
        }
        return sb.toString();
    }

    private record DatasetSpec(String name, String sql, ConnectionInfo connection, String schema) {
    }
}
