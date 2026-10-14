package io.github.eduardo.dbeaver.aichat.core.export;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Safe file names inside the export folder and all-or-nothing writes. */
public final class ExportFiles {

    static final int MAX_NAME = 100;

    private ExportFiles() {
    }

    public interface Writer {
        void write(OutputStream out) throws IOException;
    }

    /**
     * A base name with no path parts or characters that are invalid on Windows, macOS or Linux; "export" when
     * nothing usable is left. A trailing extension matching the format is dropped (it is added back).
     */
    public static String sanitize(String requested, ExportFormat format) {
        String name = requested == null ? "" : requested.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1);
        String ext = "." + format.extension();
        if (name.toLowerCase().endsWith(ext)) {
            name = name.substring(0, name.length() - ext.length());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String clean = sb.toString().replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (clean.length() > MAX_NAME) {
            clean = clean.substring(0, MAX_NAME).trim();
        }
        return clean.isEmpty() ? "export" : clean;
    }

    /**
     * Writes to a temporary file in directory and moves it to a free name ("x.pdf", "x (2).pdf", ...). On any
     * failure, including cancellation, nothing is left behind.
     */
    public static Path writeNew(Path directory, String baseName, ExportFormat format, Writer writer)
            throws IOException {
        Files.createDirectories(directory);
        Path tmp = Files.createTempFile(directory, ".kastor-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                writer.write(out);
            }
            for (int n = 1; ; n++) {
                Path target = directory.resolve(baseName + (n == 1 ? "" : " (" + n + ")") + "." + format.extension());
                if (!target.normalize().startsWith(directory.normalize())) {
                    throw new IOException("Nome de arquivo inválido: " + baseName);
                }
                if (Files.exists(target)) {
                    continue;
                }
                try {
                    // same directory, so this is a rename; without REPLACE_EXISTING it never overwrites
                    Files.move(tmp, target);
                    return target;
                } catch (FileAlreadyExistsException raced) {
                    // another file took the name between the check and the move; try the next one
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
