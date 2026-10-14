package io.github.eduardo.dbeaver.aichat.core.tools;

import java.nio.file.Path;

/** A file written by generate_file. rows = data rows written across all datasets. */
public record GeneratedFile(Path path, String format, long sizeBytes, long rows) {
}
