package com.example.database.processor;

import com.example.database.storage.DataDirectory;
import com.example.database.storage.DefaultStorageEngine;
import com.example.database.storage.StorageEngine;
import com.example.database.storage.index.FileIndexStore;
import com.example.database.storage.page.Rid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexMaintenanceTest {

    @TempDir
    Path dataDir;

    @Test
    void dmlKeepsIndexConsistentIncludingGrowingUpdate() {
        Path root = dataDir.resolve("store");
        StorageEngine engine = new DefaultStorageEngine(new DataDirectory(root));
        engine.start();
        try {
            DefaultQueryProcessor processor = new DefaultQueryProcessor(engine);
            processor.executeText("CREATE DATABASE shop");
            processor.executeText("CREATE TABLE shop.users (id INT, name VARCHAR)");
            processor.executeText("INSERT INTO shop.users VALUES (1, 'Ada')");
            processor.executeText("CREATE INDEX idx_users_id ON shop.users (id)");
            processor.executeText("UPDATE shop.users SET name = 'Ada Lovelace Extra Long' WHERE id = 1");
            processor.executeText("DELETE FROM shop.users WHERE id = 1");

            FileIndexStore indexStore = (FileIndexStore) engine.indexStore();
            List<Rid> hits = collect(indexStore.lookupEquals("shop", "users", "idx_users_id", new Object[]{1}));
            assertTrue(hits.isEmpty());
        } finally {
            engine.stop();
        }
    }

    private static List<Rid> collect(Iterator<Rid> iterator) {
        List<Rid> out = new ArrayList<>();
        iterator.forEachRemaining(out::add);
        return out;
    }
}
