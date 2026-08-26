package com.example.database.processor.lexer;

/**
 * Catalog of lexical token kinds recognized by the query lexer.
 */
public enum TokenCatalog {
    // commands / keywords
    CREATE,
    ALTER,
    DROP,
    DATABASE,
    TABLE,
    INDEX,
    COLUMN,
    ADD,
    ON,
    SELECT,
    UPDATE,
    DELETE,
    INSERT,
    INTO,
    FROM,
    SET,
    VALUES,
    WHERE,

    // column types (CREATE TABLE)
    INT,
    VARCHAR,
    BOOLEAN_TYPE,

    // literals / names
    IDENTIFIER,
    STRING,
    BOOLEAN,
    NUMBER,

    // operators / punctuation
    STAR,
    GT,
    LT,
    GTE,
    LTE,
    EQ,
    NEQ,
    SLASH,
    PLUS,
    MINUS,
    COMMA,
    DOT,
    LPAREN,
    RPAREN,
    SEMICOLON,

    EOF
}
