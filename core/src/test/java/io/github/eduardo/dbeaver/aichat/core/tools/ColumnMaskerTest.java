package io.github.eduardo.dbeaver.aichat.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ColumnMaskerTest {

    @Test
    void matchesCaseInsensitiveWithWildcards() {
        ColumnMasker m = ColumnMasker.parse("cpf, *senha*;\nemail?");
        assertTrue(m.matches("CPF"));
        assertTrue(m.matches("user_Senha_hash"));
        assertTrue(m.matches("emails"));
        assertFalse(m.matches("cpf_count"));
        assertFalse(m.matches("name"));
    }

    @Test
    void masksValuesButKeepsNull() {
        QueryResult r = new QueryResult(List.of("id", "cpf"),
                List.of(List.of("1", "123"), Arrays.asList("2", null)), true);
        QueryResult out = ColumnMasker.parse("cpf").apply(r);
        assertEquals(List.of("1", "***"), out.rows().get(0));
        assertEquals(Arrays.asList("2", null), out.rows().get(1));
        assertTrue(out.truncated());
        assertEquals("123", r.rows().get(0).get(1));
    }

    @Test
    void emptyPatternsMaskNothing() {
        assertSame(ColumnMasker.NONE, ColumnMasker.parse(" , ;"));
        assertFalse(ColumnMasker.NONE.matches("cpf"));
    }
}
