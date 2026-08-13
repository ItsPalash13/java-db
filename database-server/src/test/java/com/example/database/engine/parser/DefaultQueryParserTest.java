package com.example.database.engine.parser;

import com.example.database.engine.lexer.DefaultQueryLexer;
import com.example.database.engine.lexer.QueryLexer;
import com.example.database.engine.lexer.Token;
import com.example.database.engine.parser.ast.AstNode;
import com.example.database.engine.parser.ast.expr.BinaryExpression;
import com.example.database.engine.parser.ast.expr.ColumnExpression;
import com.example.database.engine.parser.ast.expr.LiteralExpression;
import com.example.database.engine.parser.ast.query.CreateDatabaseQuery;
import com.example.database.engine.parser.ast.query.CreateTableQuery;
import com.example.database.engine.parser.ast.query.DeleteQuery;
import com.example.database.engine.parser.ast.query.InsertQuery;
import com.example.database.engine.parser.ast.query.SelectQuery;
import com.example.database.engine.parser.ast.query.UpdateQuery;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryParserTest {

    private final QueryLexer lexer = new DefaultQueryLexer();
    private final QueryParser parser = new DefaultQueryParser();

    @Test
    void parsesSelectStar() {
        SelectQuery query = parseAs("SELECT * FROM users", SelectQuery.class);
        assertTrue(query.star());
        assertTrue(query.projections().isEmpty());
        assertEquals("users", query.table());
        assertTrue(query.where().isEmpty());
    }

    @Test
    void parsesSelectColumnsWithWhere() {
        SelectQuery query = parseAs("SELECT id, name FROM users WHERE age >= 18", SelectQuery.class);
        assertFalse(query.star());
        assertEquals(2, query.projections().size());
        assertInstanceOf(ColumnExpression.class, query.projections().get(0));
        assertEquals("id", ((ColumnExpression) query.projections().get(0)).name());
        assertEquals("users", query.table());
        BinaryExpression where = assertInstanceOf(BinaryExpression.class, query.where().orElseThrow());
        assertEquals(com.example.database.engine.lexer.TokenCatalog.GTE, where.operator());
    }

    @Test
    void parsesUpdate() {
        UpdateQuery query = parseAs(
                "UPDATE users SET name = \"Bob\" WHERE id = 1",
                UpdateQuery.class
        );
        assertEquals("users", query.table());
        assertEquals(1, query.assignments().size());
        assertEquals("name", query.assignments().get(0).column());
        assertInstanceOf(LiteralExpression.class, query.assignments().get(0).value());
        assertTrue(query.where().isPresent());
    }

    @Test
    void parsesInsertWithColumns() {
        InsertQuery query = parseAs(
                "INSERT INTO users (id, name) VALUES (1, 'Ada')",
                InsertQuery.class
        );
        assertEquals("users", query.table());
        assertEquals(List.of("id", "name"), query.columns());
        assertEquals(2, query.values().size());
        assertEquals(1L, ((LiteralExpression) query.values().get(0)).value());
        assertEquals("Ada", ((LiteralExpression) query.values().get(1)).value());
    }

    @Test
    void parsesInsertWithoutColumns() {
        InsertQuery query = parseAs(
                "INSERT INTO users VALUES (1, 'hi', FALSE)",
                InsertQuery.class
        );
        assertTrue(query.columns().isEmpty());
        assertEquals(3, query.values().size());
        assertEquals(Boolean.FALSE, ((LiteralExpression) query.values().get(2)).value());
    }

    @Test
    void parsesDelete() {
        DeleteQuery query = parseAs("DELETE FROM users WHERE id <= 10", DeleteQuery.class);
        assertTrue(query.where().isPresent());
        assertEquals("users", query.table());
    }

    @Test
    void parsesCreateDatabase() {
        CreateDatabaseQuery query = parseAs("CREATE DATABASE mydb", CreateDatabaseQuery.class);
        assertEquals("mydb", query.name());
    }

    @Test
    void parsesCreateDbAlias() {
        CreateDatabaseQuery query = parseAs("create db shop", CreateDatabaseQuery.class);
        assertEquals("shop", query.name());
    }

    @Test
    void parsesCreateTable() {
        CreateTableQuery query = parseAs("CREATE TABLE users (id, name)", CreateTableQuery.class);
        assertEquals("users", query.table());
        assertEquals(List.of("id", "name"), query.columns());
    }

    @Test
    void rejectsCreateWithoutTarget() {
        List<Token> tokens = lexer.tokenize("CREATE users");
        ParseException ex = assertThrows(ParseException.class, () -> parser.parse(tokens));
        assertEquals(7, ex.index());
        assertEquals(
                "ERROR at index 7: expected DATABASE or TABLE but found IDENTIFIER",
                ex.toResponse()
        );
    }

    @Test
    void rejectsCreateTableWithoutColumns() {
        List<Token> tokens = lexer.tokenize("CREATE TABLE users");
        ParseException ex = assertThrows(ParseException.class, () -> parser.parse(tokens));
        assertTrue(ex.toResponse().contains("expected LPAREN"));
    }

    @Test
    void rejectsMissingFromWithExactIndex() {
        List<Token> tokens = lexer.tokenize("SELECT * users");
        ParseException ex = assertThrows(ParseException.class, () -> parser.parse(tokens));
        assertTrue(ex.toResponse().contains("expected FROM"));
        assertEquals(9, ex.index());
    }

    private <T extends AstNode> T parseAs(String sql, Class<T> type) {
        AstNode ast = parser.parse(lexer.tokenize(sql));
        return assertInstanceOf(type, ast);
    }
}
