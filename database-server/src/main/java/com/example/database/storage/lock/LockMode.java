package com.example.database.storage.lock;

/**
 * Lock mode on one {@link LockKey}. Intention modes (IS/IX) live on the parent key
 * (database under table, etc.); S/X on the child.
 */
public enum LockMode {
    IS,
    IX,
    S,
    X;

    /**
     * Whether {@code requested} can be granted while {@code held} is already present
     * on the same key (compatibility matrix).
     */
    public static boolean compatible(LockMode held, LockMode requested) {
        return switch (held) {
            case IS -> requested == IS || requested == IX || requested == S;
            case IX -> requested == IS || requested == IX;
            case S -> requested == IS || requested == S;
            case X -> false;
        };
    }

    /** Parent intention for a table or row child mode: S → IS, X → IX. */
    public static LockMode intentionFor(LockMode childMode) {
        return switch (childMode) {
            case S -> IS;
            case X -> IX;
            case IS, IX -> childMode;
        };
    }

    static boolean canGrant(LockState state, LockMode requested) {
        if (state.count(IS) > 0 && !compatible(IS, requested)) {
            return false;
        }
        if (state.count(IX) > 0 && !compatible(IX, requested)) {
            return false;
        }
        if (state.count(S) > 0 && !compatible(S, requested)) {
            return false;
        }
        if (state.count(X) > 0 && !compatible(X, requested)) {
            return false;
        }
        return true;
    }
}
