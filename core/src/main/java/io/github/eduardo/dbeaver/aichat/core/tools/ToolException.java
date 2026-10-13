package io.github.eduardo.dbeaver.aichat.core.tools;

/** A problem with the tool call itself; the message is shown to the model. */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
