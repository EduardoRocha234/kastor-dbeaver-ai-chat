package io.github.eduardo.dbeaver.aichat.core.llm;

public class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    /** status is the HTTP status code, or 0 for network/stream errors. */
    public LlmException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
