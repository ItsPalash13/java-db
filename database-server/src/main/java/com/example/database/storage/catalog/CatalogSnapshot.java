package com.example.database.storage.catalog;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Point-in-time copy of catalog memory for explicit transaction rollback.
 * Does not include on-disk files — paired with {@link CatalogManager#setDeferPersist}.
 */
public final class CatalogSnapshot {

    private final Map<String, Map<String, TableMetadata>> tablesByDatabase;
    private final Set<String> databaseNames;
    private final int nextTableId;

    CatalogSnapshot(
            Map<String, Map<String, TableMetadata>> tablesByDatabase,
            Set<String> databaseNames,
            int nextTableId
    ) {
        this.tablesByDatabase = deepCopyTables(tablesByDatabase);
        this.databaseNames = new LinkedHashSet<>(databaseNames);
        this.nextTableId = nextTableId;
    }

    Map<String, Map<String, TableMetadata>> tablesByDatabase() {
        return tablesByDatabase;
    }

    Set<String> databaseNames() {
        return databaseNames;
    }

    int nextTableId() {
        return nextTableId;
    }

    public List<TableMetadata> allTables() {
        List<TableMetadata> tables = new java.util.ArrayList<>();
        for (Map<String, TableMetadata> perDatabase : tablesByDatabase.values()) {
            tables.addAll(perDatabase.values());
        }
        return List.copyOf(tables);
    }

    private static Map<String, Map<String, TableMetadata>> deepCopyTables(
            Map<String, Map<String, TableMetadata>> source
    ) {
        Map<String, Map<String, TableMetadata>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, TableMetadata>> entry : source.entrySet()) {
            copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return copy;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CatalogSnapshot that)) {
            return false;
        }
        return nextTableId == that.nextTableId
                && tablesByDatabase.equals(that.tablesByDatabase)
                && databaseNames.equals(that.databaseNames);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tablesByDatabase, databaseNames, nextTableId);
    }
}
