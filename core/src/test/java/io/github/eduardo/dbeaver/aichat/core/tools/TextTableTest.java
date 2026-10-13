package io.github.eduardo.dbeaver.aichat.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTableTest {

    @Test
    void escapesNewlinesAndPipesAndTruncatesLongCells() {
        QueryResult r = new QueryResult(List.of("a"), List.of(List.of("x|y\nz"), List.of("w".repeat(500))), false);
        String out = TextTable.format(r, 10_000);
        assertTrue(out.contains("x\\|y\\nz"), out);
        assertTrue(out.contains("w".repeat(200) + "…"), out);
        assertFalse(out.contains("w".repeat(201)), out);
    }

    @Test
    void stopsAtMaxChars() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(List.of("linha " + i));
        }
        String out = TextTable.format(new QueryResult(List.of("c"), rows, false), 500);
        assertTrue(out.length() < 650, "length " + out.length());
        assertTrue(out.contains("saída truncada"), out);
    }
}
