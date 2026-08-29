package com.example.database.processor.parser.statement;

import com.example.database.processor.lexer.TokenCatalog;
import com.example.database.processor.parser.Parser;
import com.example.database.processor.parser.QualifiedNames;
import com.example.database.processor.parser.TokenStream;
import com.example.database.processor.parser.ast.AstNode;
import com.example.database.processor.parser.ast.query.DescribeTableQuery;

/**
 * DESCRIBE | DESC shop.users
 */
public final class DescribeParser implements Parser {

    @Override
    public AstNode parse(TokenStream stream) {
        stream.expect(TokenCatalog.DESCRIBE);
        return new DescribeTableQuery(QualifiedNames.parseTable(stream));
    }
}
