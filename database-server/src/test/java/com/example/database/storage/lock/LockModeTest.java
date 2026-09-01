package com.example.database.storage.lock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockModeTest {

    @Test
    void compatibilityMatrix() {
        assertTrue(LockMode.compatible(LockMode.IS, LockMode.IS));
        assertTrue(LockMode.compatible(LockMode.IS, LockMode.IX));
        assertTrue(LockMode.compatible(LockMode.IS, LockMode.S));
        assertFalse(LockMode.compatible(LockMode.IS, LockMode.X));

        assertTrue(LockMode.compatible(LockMode.IX, LockMode.IX));
        assertFalse(LockMode.compatible(LockMode.IX, LockMode.S));
        assertFalse(LockMode.compatible(LockMode.S, LockMode.IX));

        assertTrue(LockMode.compatible(LockMode.S, LockMode.S));
        assertFalse(LockMode.compatible(LockMode.S, LockMode.X));
        assertFalse(LockMode.compatible(LockMode.X, LockMode.S));
    }

    @Test
    void intentionForChildMode() {
        assertEquals(LockMode.IS, LockMode.intentionFor(LockMode.S));
        assertEquals(LockMode.IX, LockMode.intentionFor(LockMode.X));
    }

    private static void assertEquals(LockMode expected, LockMode actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
