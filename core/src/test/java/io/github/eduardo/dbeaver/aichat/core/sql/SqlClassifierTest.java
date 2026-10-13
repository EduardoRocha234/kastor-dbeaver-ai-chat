package io.github.eduardo.dbeaver.aichat.core.sql;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "select 1",
            "SELECT * FROM t;",
            "  -- comment\nselect a from b",
            "/* x */ SELECT 1",
            "with x as (select 1) select * from x",
            "show tables",
            "explain select * from t",
            "describe t",
            "values (1)",
            "select 'delete from t' as s",
            "select \"update\" from t",
            "select [delete] from t",
            "select `insert` from t",
            "select 'it''s' from t",
            "select 1;  ;",
            "select $$ ; delete $$",
            "select $tag$ x; delete from t $tag$",
            "select a -- trailing comment ; delete\nfrom t",
            "SELECT * FROM orders WHERE status = 'shipped' ORDER BY created_at DESC LIMIT 10"
    })
    void readStatements(String sql) {
        assertEquals(SqlKind.READ, SqlClassifier.classify(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "delete from t",
            "update t set a = 1",
            "insert into t values (1)",
            "drop table t",
            "create table x (a int)",
            "alter table t add b int",
            "truncate t",
            "call proc()",
            "pragma foreign_keys = off",
            "grant select on t to u",
            "merge into t using s on 1 = 1 when matched then delete",
            "select 1; delete from t",
            "with d as (delete from t returning *) select * from d",
            "select * into new_t from t",
            "select * from t for update",
            "explain analyze delete from t",
            "explain analyze select 1",
            "select 1 /* unterminated",
            "select 'unterminated",
            "select 1 /*! ; delete from t */",
            "select 1--1; delete from t",
            "select 'a\\'; delete from t; --'",
            // Dialects disagree on where comments and strings end: fail closed.
            "SELECT 1 --'\n; DELETE FROM t; COMMIT; --'",
            "SELECT E'\\''; DELETE FROM t; COMMIT; --'",
            "SELECT '\\''; DROP TABLE t; -- '",
            "SELECT 1 # '\n; DROP TABLE t; -- '",
            "explain analyse select f()"
    })
    void writeStatements(String sql) {
        assertEquals(SqlKind.WRITE, SqlClassifier.classify(sql), sql);
    }

    @Test
    void countsStatements() {
        assertEquals(1, SqlClassifier.statementCount("select 1;"));
        assertEquals(2, SqlClassifier.statementCount("delete from a; delete from b"));
        assertEquals(1, SqlClassifier.statementCount("insert into t values (';')"));
        assertEquals(-1, SqlClassifier.statementCount("select 'x"));
    }
}
