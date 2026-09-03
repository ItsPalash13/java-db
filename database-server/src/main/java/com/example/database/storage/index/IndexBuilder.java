package com.example.database.storage.index;

import com.example.database.processor.executor.engine.volcano.Tuple;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.page.Rid;
import com.example.database.storage.table.TableStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Bulk-builds a secondary index by scanning the heap after CREATE INDEX.
 * Uses sort-build ({@link IndexSortedBuilder}) for {@link FileIndexStore}.
 */
public final class IndexBuilder {

    private IndexBuilder() {
    }

    public static void bulkBuild(
            TableStore tableStore,
            IndexStore indexStore,
            String database,
            String table,
            IndexMetadata index,
            TableMetadata tableMetadata
    ) {
        Objects.requireNonNull(tableStore, "tableStore");
        Objects.requireNonNull(indexStore, "indexStore");
        ColumnType[] keyTypes = IndexKeySupport.keyTypes(tableMetadata, index);
        if (indexStore instanceof FileIndexStore fileIndexStore) {
            fileIndexStore.registerKeyTypes(database, table, index, keyTypes);
            List<BTreeLeafPage.LeafEntry> entries = collectEntries(
                    tableStore,
                    database,
                    table,
                    index,
                    tableMetadata,
                    keyTypes
            );
            fileIndexStore.bulkLoadSorted(database, table, index.name(), entries, index.unique());
            return;
        }
        Iterator<Tuple> rows = tableStore.scan(database, table);
        while (rows.hasNext()) {
            Tuple row = rows.next();
            Optional<Rid> rid = tableStore.findRid(database, table, row.rowId());
            if (rid.isEmpty()) {
                continue;
            }
            Object[] key = IndexKeySupport.keyValues(tableMetadata, index, row.values());
            indexStore.insert(database, table, index.name(), key, rid.get());
        }
    }

    private static List<BTreeLeafPage.LeafEntry> collectEntries(
            TableStore tableStore,
            String database,
            String table,
            IndexMetadata index,
            TableMetadata tableMetadata,
            ColumnType[] keyTypes
    ) {
        List<BTreeLeafPage.LeafEntry> entries = new ArrayList<>();
        Iterator<Tuple> rows = tableStore.scan(database, table);
        while (rows.hasNext()) {
            Tuple row = rows.next();
            Optional<Rid> rid = tableStore.findRid(database, table, row.rowId());
            if (rid.isEmpty()) {
                continue;
            }
            Object[] key = IndexKeySupport.keyValues(tableMetadata, index, row.values());
            byte[] keyBytes = IndexKeyCodec.encode(key, keyTypes);
            entries.add(new BTreeLeafPage.LeafEntry(keyBytes, rid.get(), key));
        }
        entries.sort(Comparator.comparing(BTreeLeafPage.LeafEntry::keyBytes, (a, b) -> IndexKeyCodec.compare(a, b, keyTypes)));
        return entries;
    }
}
