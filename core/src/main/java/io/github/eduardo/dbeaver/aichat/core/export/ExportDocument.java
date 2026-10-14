package io.github.eduardo.dbeaver.aichat.core.export;

import java.util.List;

/** A report independent of the file format: a title followed by blocks. */
public record ExportDocument(String title, List<Block> blocks) {

    public sealed interface Block permits Heading, Paragraph, ListBlock, CodeBlock, TableBlock {
    }

    /** level 1..3 */
    public record Heading(int level, String text) implements Block {
    }

    public record Paragraph(List<Run> runs) implements Block {
    }

    public record ListBlock(boolean ordered, List<List<Run>> items) implements Block {
    }

    public record CodeBlock(String text) implements Block {
    }

    /** note = shown under the table (e.g. truncation); null for none. */
    public record TableBlock(List<String> columns, List<List<Object>> rows, String note) implements Block {
    }

    public record Run(String text, boolean bold, boolean italic, boolean code) {

        public static Run plain(String text) {
            return new Run(text, false, false, false);
        }
    }
}
