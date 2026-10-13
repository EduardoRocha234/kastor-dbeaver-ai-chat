package io.github.eduardo.dbeaver.aichat.core.mention;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.mention.Mentions.Query;
import io.github.eduardo.dbeaver.aichat.core.tools.ContainerInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.TableInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MentionsTest {

    /** A PostgreSQL server: databases at the root, schemas loaded only for base_dev. */
    private static Map<String, List<ContainerInfo>> server() {
        Map<String, List<ContainerInfo>> c = new HashMap<>();
        c.put("", List.of(new ContainerInfo("base_dev", true), new ContainerInfo("login_dev", true)));
        c.put("base_dev", List.of(new ContainerInfo("public", false), new ContainerInfo("el_acesso", false)));
        return c;
    }

    @Test
    void queryAtCaretAfterAt() {
        Query q = Mentions.queryAt("veja @base_d", 12);
        assertEquals(5, q.start());
        assertEquals("base_d", q.text());
    }

    @Test
    void queryAtStartOfText() {
        assertEquals("", Mentions.queryAt("@", 1).text());
    }

    @Test
    void noQueryWhenAtIsPartOfWordOrSeparatedBySpace() {
        assertNull(Mentions.queryAt("mail a@b", 8));
        assertNull(Mentions.queryAt("@base fim", 9));
        assertNull(Mentions.queryAt("sem arroba", 5));
    }

    @Test
    void rootNotLoadedAsksForIt() {
        Suggestions s = Mentions.suggest("", Map.of(), Map.of(), 50);
        assertEquals("", s.pendingContainers());
        assertTrue(s.items().isEmpty());
    }

    @Test
    void rootListsDatabasesFiltered() {
        Suggestions s = Mentions.suggest("log", server(), Map.of(), 50);
        assertEquals(List.of("@login_dev."), replacements(s));
        assertEquals("database", s.items().get(0).type());
        assertTrue(s.items().get(0).drill());
    }

    @Test
    void databaseWithoutLoadedSchemasAsksOnlyForThatDatabase() {
        Suggestions s = Mentions.suggest("login_dev.", server(), Map.of(), 50);
        assertEquals("login_dev", s.pendingContainers());
    }

    @Test
    void databasePathListsItsSchemas() {
        Suggestions s = Mentions.suggest("base_dev.ace", server(), Map.of(), 50);
        assertEquals(List.of("@base_dev.el_acesso."), replacements(s));
        assertEquals("schema", s.items().get(0).type());
    }

    @Test
    void schemaPathListsItsTablesFilteredAndPrefixFirst() {
        Map<String, List<TableInfo>> tables = Map.of("base_dev.public", List.of(
                new TableInfo("usuario_perfil", "TABLE"), new TableInfo("perfil", "TABLE"), new TableInfo("pessoa", "TABLE")));
        Suggestions s = Mentions.suggest("base_dev.public.perf", server(), tables, 50);
        assertEquals(List.of("@base_dev.public.perfil ", "@base_dev.public.usuario_perfil "), replacements(s));
        assertFalse(s.items().get(0).drill());
    }

    @Test
    void schemaPathWithoutLoadedTablesAsksForThem() {
        Suggestions s = Mentions.suggest("BASE_DEV.PUBLIC.", server(), Map.of(), 50);
        assertEquals("base_dev.public", s.pendingTables());
    }

    @Test
    void rootSchemasHoldTablesDirectly() {
        Map<String, List<ContainerInfo>> c = Map.of("", List.of(new ContainerInfo("public", false), new ContainerInfo("sales", false)));
        assertEquals(List.of("@sales."), replacements(Mentions.suggest("sal", c, Map.of(), 50)));
        assertEquals("public", Mentions.suggest("public.", c, Map.of(), 50).pendingTables());
    }

    @Test
    void loadedTablesAlsoMatchByNameAtTopLevel() {
        Map<String, List<TableInfo>> tables = Map.of("base_dev.public", List.of(new TableInfo("perfil", "TABLE")));
        Suggestions s = Mentions.suggest("perfil", server(), tables, 50);
        assertEquals(List.of("@base_dev.public.perfil "), replacements(s));
    }

    @Test
    void connectionWithoutContainersListsTablesDirectly() {
        Map<String, List<ContainerInfo>> none = Map.of("", List.of());
        assertEquals("", Mentions.suggest("ord", none, Map.of(), 50).pendingTables());
        Suggestions s = Mentions.suggest("ord", none, Map.of("", List.of(new TableInfo("orders", "TABLE"))), 50);
        assertEquals(List.of("@orders "), replacements(s));
    }

    @Test
    void respectsLimit() {
        assertEquals(1, Mentions.suggest("", server(), Map.of(), 1).items().size());
    }

    @Test
    void schemaPathsComeFromLoadedTableContainers() {
        assertEquals(List.of("base_dev.public", "base_dev.el_acesso"), Mentions.schemaPaths(server()));
    }

    @Test
    void parsesTableAndSchemaMentions() {
        List<Mention> m = Mentions.parse("Como @base_dev.public.perfil se liga a @base_dev.el_acesso?",
                List.of("base_dev.public", "base_dev.el_acesso"));
        assertEquals(List.of(new Mention("base_dev.public", "perfil"), new Mention("base_dev.el_acesso", null)), m);
    }

    @Test
    void parseKeepsUnknownNamesAsTypedObjects() {
        assertEquals(List.of(new Mention(null, "orders")), Mentions.parse("conte @orders.", List.of()));
    }

    @Test
    void parseIgnoresEmailsAndDuplicates() {
        assertEquals(List.of(new Mention("base_dev.public", null)),
                Mentions.parse("a@b.com @base_dev.public e @base_dev.public", List.of("base_dev.public")));
    }

    private static List<String> replacements(Suggestions s) {
        return s.items().stream().map(Suggestion::replacement).toList();
    }
}
