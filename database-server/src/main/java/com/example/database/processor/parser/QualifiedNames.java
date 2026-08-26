package com.example.database.processor.parser;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.ast.QualifiedTable;

/**
 * Shared {@code ident DOT ident} parse for every table reference. Database and index names
 * stay a single identifier — only tables are qualified in this slice.
 */
public final class QualifiedNames {

    private QualifiedNames() {
    }

    public static QualifiedTable parseTable(TokenStream stream) {
        String database = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
        // Unqualified users is rejected here so every statement parser shares one rule.
        stream.expect(TokenCatalog.DOT);
        String table = stream.expect(TokenCatalog.IDENTIFIER).lexeme();
        return new QualifiedTable(database, table);
    }
}
