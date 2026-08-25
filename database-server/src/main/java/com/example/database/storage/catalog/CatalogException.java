package com.example.database.storage.catalog;

/**
 * Catalog rule violation (duplicate table, duplicate column, empty column list).
 */
public final class CatalogException extends RuntimeException {

    public CatalogException(String detail) {
        super(detail);
    }
}
