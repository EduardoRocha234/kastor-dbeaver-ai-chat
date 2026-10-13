package io.github.eduardo.dbeaver.aichat.core;

public class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CancelledException() {
        super("Cancelado");
    }
}
