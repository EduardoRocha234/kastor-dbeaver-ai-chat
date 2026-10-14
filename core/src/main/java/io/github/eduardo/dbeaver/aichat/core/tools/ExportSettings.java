package io.github.eduardo.dbeaver.aichat.core.tools;

import java.nio.file.Path;

/**
 * Where generate_file writes and who hears about it. directory null = export disabled. maxRows caps each dataset;
 * onFile is told about every file written (the chat shows it to the user).
 */
public record ExportSettings(Path directory, int maxRows, FileListener onFile) {

    public static final int DEFAULT_MAX_ROWS = 100_000;
    public static final ExportSettings NONE = new ExportSettings(null, DEFAULT_MAX_ROWS, file -> {
    });

    public interface FileListener {
        void fileGenerated(GeneratedFile file);
    }
}
