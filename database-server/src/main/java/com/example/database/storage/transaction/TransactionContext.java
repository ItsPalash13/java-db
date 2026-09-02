package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogSnapshot;

/**
 * Per-thread transaction session for explicit {@code BEGIN} or implicit single-statement txns.
 */
final class TransactionContext {

    enum Mode {
        NONE,
        IMPLICIT,
        EXPLICIT
    }

    private Mode mode = Mode.NONE;
    private int txnId;
    private CatalogSnapshot catalogSnapshot;
    private IsolationLevel isolationLevel = IsolationLevel.READ_COMMITTED;

    Mode mode() {
        return mode;
    }

    int txnId() {
        return txnId;
    }

    CatalogSnapshot catalogSnapshot() {
        return catalogSnapshot;
    }

    IsolationLevel isolationLevel() {
        return isolationLevel;
    }

    void setIsolationLevel(IsolationLevel isolationLevel) {
        this.isolationLevel = isolationLevel;
    }

    void beginImplicit(int txnId) {
        this.mode = Mode.IMPLICIT;
        this.txnId = txnId;
        this.catalogSnapshot = null;
    }

    void beginExplicit(int txnId, CatalogSnapshot catalogSnapshot) {
        this.mode = Mode.EXPLICIT;
        this.txnId = txnId;
        this.catalogSnapshot = catalogSnapshot;
    }

    void clear() {
        mode = Mode.NONE;
        txnId = 0;
        catalogSnapshot = null;
    }

    boolean active() {
        return mode != Mode.NONE;
    }

    boolean explicitMode() {
        return mode == Mode.EXPLICIT;
    }
}
