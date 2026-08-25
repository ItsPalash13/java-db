package com.example.database.storage.catalog;

/**
 * Catalog rule violation or catalog persistence failure.
 */
public final class CatalogException extends RuntimeException {

    public CatalogException(String detail) {
        super(detail);
    }

    public CatalogException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
