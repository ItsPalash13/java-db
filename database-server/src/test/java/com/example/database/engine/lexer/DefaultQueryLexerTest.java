package com.example.database.engine.lexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryLexerTest {

    private final QueryLexer lexer = new DefaultQueryLexer();

    @Test
    void tokenizesCreateDatabase() {
        assertKinds(
                "CREATE DATABASE mydb",
                TokenCatalog.CREATE,
                TokenCatalog.DATABASE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EOF
        );
        List<Token> tokens = lexer.tokenize("CREATE DATABASE mydb");
        assertEquals("DATABASE", tokens.get(1).lexeme());
        assertEquals("mydb", tokens.get(2).lexeme());
    }

    @Test
    void tokenizesCreateDbAlias() {
        List<Token> tokens = lexer.tokenize("create db shop");
        assertEquals(TokenCatalog.CREATE, tokens.get(0).kind());
        assertEquals(TokenCatalog.DATABASE, tokens.get(1).kind());
        assertEquals("DATABASE", tokens.get(1).lexeme());
        assertEquals("shop", tokens.get(2).lexeme());
        assertEquals(TokenCatalog.EOF, tokens.get(3).kind());
    }

    @Test
    void tokenizesCreateTable() {
        assertKinds(
                "CREATE TABLE users (id, name)",
                TokenCatalog.CREATE,
                TokenCatalog.TABLE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.LPAREN,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.COMMA,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.RPAREN,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesCreateIndex() {
        assertKinds(
                "CREATE INDEX idx_users ON users (id, name)",
                TokenCatalog.CREATE,
                TokenCatalog.INDEX,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.ON,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.LPAREN,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.COMMA,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.RPAREN,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesDropTable() {
        assertKinds(
                "DROP TABLE users",
                TokenCatalog.DROP,
                TokenCatalog.TABLE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesAlterTableAddColumn() {
        assertKinds(
                "ALTER TABLE users ADD COLUMN age",
                TokenCatalog.ALTER,
                TokenCatalog.TABLE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.ADD,
                TokenCatalog.COLUMN,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesSelectStarFrom() {
        assertKinds(
                "SELECT * FROM users",
                TokenCatalog.SELECT,
                TokenCatalog.STAR,
                TokenCatalog.FROM,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesSelectColumnsWhere() {
        assertKinds(
                "SELECT id, name FROM users WHERE age > 18",
                TokenCatalog.SELECT,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.COMMA,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.FROM,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.WHERE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.GT,
                TokenCatalog.NUMBER,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesSelectWithBooleanAndString() {
        List<Token> tokens = lexer.tokenize(
                "SELECT * FROM users WHERE active = TRUE AND name = 'Ada'"
        );
        assertEquals(TokenCatalog.BOOLEAN, tokens.get(7).kind());
        assertEquals("true", tokens.get(7).lexeme());
        assertEquals(TokenCatalog.STRING, tokens.get(11).kind());
        assertEquals("Ada", tokens.get(11).lexeme());
        assertEquals(TokenCatalog.EOF, tokens.get(tokens.size() - 1).kind());
    }

    @Test
    void tokenizesUpdateSetWhere() {
        assertKinds(
                "UPDATE users SET name = \"Bob\" WHERE id = 1",
                TokenCatalog.UPDATE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.SET,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EQ,
                TokenCatalog.STRING,
                TokenCatalog.WHERE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EQ,
                TokenCatalog.NUMBER,
                TokenCatalog.EOF
        );
        assertEquals("Bob", lexer.tokenize("UPDATE users SET name = \"Bob\" WHERE id = 1").get(5).lexeme());
    }

    @Test
    void tokenizesDeleteFromWhere() {
        assertKinds(
                "DELETE FROM users WHERE id <= 10",
                TokenCatalog.DELETE,
                TokenCatalog.FROM,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.WHERE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.LTE,
                TokenCatalog.NUMBER,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesInsertIntoValues() {
        assertKinds(
                "INSERT INTO users VALUES (1, 'hi', FALSE)",
                TokenCatalog.INSERT,
                TokenCatalog.INTO,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.VALUES,
                TokenCatalog.LPAREN,
                TokenCatalog.NUMBER,
                TokenCatalog.COMMA,
                TokenCatalog.STRING,
                TokenCatalog.COMMA,
                TokenCatalog.BOOLEAN,
                TokenCatalog.RPAREN,
                TokenCatalog.EOF
        );
        List<Token> tokens = lexer.tokenize("INSERT INTO users VALUES (1, 'hi', FALSE)");
        assertEquals("hi", tokens.get(7).lexeme());
        assertEquals("false", tokens.get(9).lexeme());
    }

    @Test
    void tokenizesAllComparisonOperators() {
        assertKinds(
                "a > b < c >= d <= e = f != g <> h",
                TokenCatalog.IDENTIFIER,
                TokenCatalog.GT,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.LT,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.GTE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.LTE,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EQ,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.NEQ,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.NEQ,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesArithmeticAndPunctuation() {
        assertKinds(
                "1 + 2 - 3 / 4 * 5 ;",
                TokenCatalog.NUMBER,
                TokenCatalog.PLUS,
                TokenCatalog.NUMBER,
                TokenCatalog.MINUS,
                TokenCatalog.NUMBER,
                TokenCatalog.SLASH,
                TokenCatalog.NUMBER,
                TokenCatalog.STAR,
                TokenCatalog.NUMBER,
                TokenCatalog.SEMICOLON,
                TokenCatalog.EOF
        );
    }

    @Test
    void tokenizesDecimalNumber() {
        List<Token> tokens = lexer.tokenize("SELECT 3.14");
        assertEquals(TokenCatalog.NUMBER, tokens.get(1).kind());
        assertEquals("3.14", tokens.get(1).lexeme());
    }

    @Test
    void tokenizesEmptyAndWhitespaceAsEofOnly() {
        assertKinds("", TokenCatalog.EOF);
        assertKinds("   \t\n  ", TokenCatalog.EOF);
    }

    @Test
    void isCaseInsensitiveForKeywords() {
        assertKinds(
                "SeLeCt * FrOm Users",
                TokenCatalog.SELECT,
                TokenCatalog.STAR,
                TokenCatalog.FROM,
                TokenCatalog.IDENTIFIER,
                TokenCatalog.EOF
        );
        assertEquals("Users", lexer.tokenize("SeLeCt * FrOm Users").get(3).lexeme());
    }

    @Test
    void preservesIdentifierCaseAndTokenIndexes() {
        List<Token> tokens = lexer.tokenize("SELECT MyCol");
        assertEquals(0, tokens.get(0).index());
        assertEquals(7, tokens.get(1).index());
        assertEquals("MyCol", tokens.get(1).lexeme());
        assertEquals(12, tokens.get(2).index());
    }

    @ParameterizedTest
    @CsvSource({
            "SELECT @ x, 7, unexpected character '@'",
            "UPDATE users SET x = #, 21, unexpected character '#'",
            "SELECT 'oops, 7, unclosed string literal",
            "INSERT INTO t VALUES (\"x, 22, unclosed string literal"
    })
    void reportsExactIndexOnLexErrors(String query, int index, String detail) {
        LexException ex = assertThrows(LexException.class, () -> lexer.tokenize(query));
        assertEquals(index, ex.index());
        assertEquals("ERROR at index " + index + ": " + detail, ex.toResponse());
    }

    @Test
    void rejectsNullQuery() {
        LexException ex = assertThrows(LexException.class, () -> lexer.tokenize(null));
        assertEquals(0, ex.index());
        assertTrue(ex.toResponse().contains("query is null"));
    }

    @Test
    void rejectsInvalidNumberEndingWithDot() {
        LexException ex = assertThrows(LexException.class, () -> lexer.tokenize("SELECT 12."));
        assertEquals(7, ex.index());
        assertEquals("ERROR at index 7: invalid number '12.'", ex.toResponse());
    }

    @Test
    void rejectsNumberStuckToIdentifier() {
        LexException ex = assertThrows(LexException.class, () -> lexer.tokenize("SELECT 1abc"));
        assertEquals(7, ex.index());
        assertTrue(ex.toResponse().startsWith("ERROR at index 7: invalid number near "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE DATABASE app",
            "CREATE DB app",
            "CREATE TABLE t (a, b)",
            "CREATE INDEX i ON t (a)",
            "DROP TABLE t",
            "DROP DATABASE app",
            "DROP INDEX i",
            "ALTER TABLE t ADD age",
            "ALTER TABLE t DROP COLUMN age",
            "SELECT * FROM t",
            "SELECT a FROM t WHERE b = 1",
            "UPDATE t SET a = 1 WHERE b != 2",
            "DELETE FROM t WHERE a < 5",
            "INSERT INTO t VALUES (1, 'x', TRUE)",
            "SELECT * FROM t WHERE x >= 1.5 AND y <= 9"
    })
    void acceptsRepresentativeQueries(String query) {
        List<Token> tokens = lexer.tokenize(query);
        assertEquals(TokenCatalog.EOF, tokens.get(tokens.size() - 1).kind());
        assertTrue(tokens.size() >= 2);
    }

    private void assertKinds(String query, TokenCatalog... expected) {
        List<Token> tokens = lexer.tokenize(query);
        List<TokenCatalog> actual = tokens.stream().map(Token::kind).toList();
        assertEquals(Arrays.asList(expected), actual, () -> "tokens=" + tokens);
    }
}
