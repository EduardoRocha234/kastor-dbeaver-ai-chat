package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a successful generate_file result says, read back from the saved conversation so the chat can show the
 * file card again when the conversation is reopened.
 */
public record GenerateFileResult(String fileName, String format, long rows) {

    public static final String TOOL_NAME = "generate_file";
    static final String PREFIX = "Arquivo gerado e mostrado ao usuário: ";
    private static final Pattern SUMMARY = Pattern.compile(
            Pattern.quote(PREFIX) + "(.+\\.(xlsx|csv|pdf|docx)) \\((\\d+) linha");

    public static Optional<GenerateFileResult> parse(String toolResult) {
        if (toolResult == null) {
            return Optional.empty();
        }
        Matcher m = SUMMARY.matcher(toolResult);
        if (!m.lookingAt()) {
            return Optional.empty();
        }
        return Optional.of(new GenerateFileResult(m.group(1), m.group(2), Long.parseLong(m.group(3))));
    }
}
