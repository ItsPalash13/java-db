package com.example.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScriptRunnerTest {

    @Test
    void normalizeSkipsBlankAndComments() {
        assertNull(ScriptRunner.normalizeStatement(""));
        assertNull(ScriptRunner.normalizeStatement("   "));
        assertNull(ScriptRunner.normalizeStatement("# comment"));
        assertNull(ScriptRunner.normalizeStatement("-- comment"));
        assertEquals("SELECT 1", ScriptRunner.normalizeStatement("  SELECT 1  "));
    }
}
