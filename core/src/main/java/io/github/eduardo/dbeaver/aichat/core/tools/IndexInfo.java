package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

public record IndexInfo(String name, boolean unique, List<String> columns) {
}
