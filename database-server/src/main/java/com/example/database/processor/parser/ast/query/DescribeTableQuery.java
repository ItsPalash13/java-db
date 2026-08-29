package com.example.database.processor.parser.ast.query;

import com.example.database.processor.parser.ast.QualifiedTable;
import com.example.database.processor.parser.ast.Query;

/**
 * {@code DESCRIBE shop.users} — column metadata for one table.
 */
public record DescribeTableQuery(QualifiedTable table) implements Query {
}
