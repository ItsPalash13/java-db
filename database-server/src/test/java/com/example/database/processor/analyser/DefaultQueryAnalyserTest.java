package com.example.database.processor.analyser;

import com.example.database.processor.parser.ast.query.SelectQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultQueryAnalyserTest {

    @Test
    void analyseReturnsTrueForAst() {
        QueryAnalyser analyser = new DefaultQueryAnalyser();
        assertTrue(analyser.analyse(new SelectQuery(true, java.util.List.of(), "users", null)));
    }
}
