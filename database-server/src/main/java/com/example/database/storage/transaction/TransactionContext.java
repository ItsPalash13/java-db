package com.example.database.storage.transaction;

import com.example.database.storage.catalog.CatalogSnapshot;
import com.example.database.storage.table.TableSnapshot;

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
    private TableSnapshot tableSnapshot;

    Mode mode() {
        return mode;
    }

    int txnId() {
        return txnId;
    }

    CatalogSnapshot catalogSnapshot() {
        return catalogSnapshot;
    }

    TableSnapshot tableSnapshot() {
        return tableSnapshot;
    }

    void beginImplicit(int txnId) {
        this.mode = Mode.IMPLICIT;
        this.txnId = txnId;
        this.catalogSnapshot = null;
        this.tableSnapshot = null;
    }

    void beginExplicit(int txnId, CatalogSnapshot catalogSnapshot, TableSnapshot tableSnapshot) {
        this.mode = Mode.EXPLICIT;
        this.txnId = txnId;
        this.catalogSnapshot = catalogSnapshot;
        this.tableSnapshot = tableSnapshot;
    }

    void clear() {
        mode = Mode.NONE;
        txnId = 0;
        catalogSnapshot = null;
        tableSnapshot = null;
    }

    boolean active() {
        return mode != Mode.NONE;
    }

    boolean explicitMode() {
        return mode == Mode.EXPLICIT;
    }
}
