package io.github.eduardo.dbeaver.aichat.core.llm;

import java.io.IOException;

/** An error reported inside an otherwise successful SSE stream. */
public class StreamErrorException extends IOException {

    private static final long serialVersionUID = 1L;

    public StreamErrorException(String message) {
        super(message);
    }
}
