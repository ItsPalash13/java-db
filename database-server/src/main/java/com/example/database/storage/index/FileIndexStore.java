package com.example.database.storage.index;

import com.example.database.storage.bufferpool.BufferFrame;
import com.example.database.storage.bufferpool.BufferPool;
import com.example.database.storage.bufferpool.PageId;
import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.catalog.IndexMetadata;
import com.example.database.storage.catalog.TableMetadata;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageType;
import com.example.database.storage.page.Rid;
import com.example.database.storage.physical.PhysicalStorage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * On-disk B+ tree index store: one {@code .idx} file per catalog index.
 * Page 0 is {@link IndexMetaPage}; tree nodes start at page 1.
 */
public final class FileIndexStore implements IndexStore {

    private final BufferPool bufferPool;
    private final PhysicalStorage physicalStorage;
    private final int pageSize;
    private final Map<String, ColumnType[]> keyTypesByIndex = new ConcurrentHashMap<>();
    private final Map<String, Boolean> uniqueByIndex = new ConcurrentHashMap<>();

    public FileIndexStore(BufferPool bufferPool, PhysicalStorage physicalStorage) {
        this.bufferPool = Objects.requireNonNull(bufferPool, "bufferPool");
        this.physicalStorage = Objects.requireNonNull(physicalStorage, "physicalStorage");
        this.pageSize = physicalStorage.pageSize();
    }

    @Override
    public void createIndex(String database, String table, IndexMetadata index, ColumnType[] keyTypes) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(keyTypes, "keyTypes");
        String file = IndexFiles.idxPath(database, table, index.name());
        int slash = file.lastIndexOf('/');
        if (slash > 0) {
            physicalStorage.createDirectory(file.substring(0, slash));
        }
        if (physicalStorage.exists(file)) {
            throw new IndexStoreException("index file already exists: " + file);
        }
        physicalStorage.create(file);
        BufferFrame metaFrame = bufferPool.newPage(file, PageType.INDEX_META);
        try {
            bufferPool.latchExclusive(metaFrame);
            try {
                IndexMetaPage meta = IndexMetaPage.wrap(metaFrame.data());
                meta.setRoot(-1, 0);
                meta.setPageSize(pageSize);
                bufferPool.markDirty(metaFrame);
            } finally {
                bufferPool.unlatch(metaFrame);
            }
        } finally {
            bufferPool.unpin(metaFrame);
        }
        String indexKey = IndexFiles.indexKey(database, table, index.name());
        keyTypesByIndex.put(indexKey, keyTypes.clone());
        uniqueByIndex.put(indexKey, index.unique());
    }

    @Override
    public void dropIndex(String database, String table, String indexName) {
        String file = IndexFiles.idxPath(database, table, indexName);
        String key = IndexFiles.indexKey(database, table, indexName);
        keyTypesByIndex.remove(key);
        uniqueByIndex.remove(key);
        if (physicalStorage.exists(file)) {
            bufferPool.flushAll();
            physicalStorage.delete(file);
        }
    }

    @Override
    public void dropTableIndexes(String database, String table, List<IndexMetadata> indexes) {
        for (IndexMetadata index : indexes) {
            dropIndex(database, table, index.name());
        }
    }

    @Override
    public void insert(String database, String table, String indexName, Object[] key, Rid rid) {
        // Concurrent DROP INDEX can unregister key types while undo/DML still references the name.
        ColumnType[] types = keyTypesOrNull(database, table, indexName);
        if (types == null) {
            return;
        }
        byte[] keyBytes = IndexKeyCodec.encode(key, types);
        String file = IndexFiles.idxPath(database, table, indexName);
        synchronized (lockFor(file)) {
            enforceUniqueOnInsert(file, database, table, indexName, keyBytes, types);
            MetaView meta = readMeta(file);
            if (meta.rootPageId() < 0) {
                int leafPageId = allocatePage(file, PageType.INDEX_LEAF);
                insertIntoLeaf(file, leafPageId, keyBytes, rid, types);
                writeMeta(file, leafPageId, 1);
                return;
            }
            SplitResult split = insertRecursive(file, meta.rootPageId(), meta.height(), keyBytes, rid, types);
            if (split == null) {
                return;
            }
            int newRoot = allocatePage(file, PageType.INDEX_INTERNAL);
            writeNewInternalRoot(file, newRoot, split.leftChild(), split.separatorKey(), split.rightChild());
            writeMeta(file, newRoot, meta.height() + 1);
        }
    }

    @Override
    public void delete(String database, String table, String indexName, Object[] key, Rid rid) {
        ColumnType[] types = keyTypesOrNull(database, table, indexName);
        if (types == null) {
            return;
        }
        byte[] keyBytes = IndexKeyCodec.encode(key, types);
        String file = IndexFiles.idxPath(database, table, indexName);
        synchronized (lockFor(file)) {
            MetaView meta = readMeta(file);
            if (meta.rootPageId() < 0) {
                return;
            }
            int leafPageId = findLeafPage(file, meta.rootPageId(), meta.height(), keyBytes, types);
            if (!removeFromLeaf(file, leafPageId, keyBytes, rid, types)) {
                return;
            }
            rebalanceAfterLeafDelete(file, meta.rootPageId(), meta.height(), leafPageId, types);
            maybeShrinkRoot(file, types);
        }
    }

    @Override
    public Iterator<Rid> lookupEquals(String database, String table, String indexName, Object[] key) {
        ColumnType[] types = requireKeyTypes(database, table, indexName);
        byte[] keyBytes = IndexKeyCodec.encode(key, types);
        String file = IndexFiles.idxPath(database, table, indexName);
        synchronized (lockFor(file)) {
            MetaView meta = readMeta(file);
            if (meta.rootPageId() < 0) {
                return List.<Rid>of().iterator();
            }
            List<Rid> matches = new ArrayList<>();
            int leafPageId = findLeafPage(file, meta.rootPageId(), meta.height(), keyBytes, types);
            collectMatchesOnLeaf(file, leafPageId, keyBytes, types, matches);
            return matches.iterator();
        }
    }

    @Override
    public Iterator<Rid> lookupRange(String database, String table, String indexName, IndexRange range) {
        Objects.requireNonNull(range, "range");
        ColumnType[] types = requireKeyTypes(database, table, indexName);
        String file = IndexFiles.idxPath(database, table, indexName);
        synchronized (lockFor(file)) {
            MetaView meta = readMeta(file);
            if (meta.rootPageId() < 0) {
                return List.<Rid>of().iterator();
            }
            List<Rid> matches = new ArrayList<>();
            int startLeaf = range.lowKey() == null
                    ? findLeftmostLeaf(file, meta.rootPageId(), meta.height())
                    : findLeafPage(
                            file,
                            meta.rootPageId(),
                            meta.height(),
                            IndexKeyCodec.encode(padKey(range.lowKey(), types), types),
                            types
                    );
            collectRangeOnLeafChain(file, startLeaf, range, types, matches);
            return matches.iterator();
        }
    }

    /**
     * Bulk-load a sorted entry list into an empty index file (sort-build CREATE INDEX).
     */
    public void bulkLoadSorted(
            String database,
            String table,
            String indexName,
            List<BTreeLeafPage.LeafEntry> sortedEntries,
            boolean unique
    ) {
        ColumnType[] types = requireKeyTypes(database, table, indexName);
        String file = IndexFiles.idxPath(database, table, indexName);
        synchronized (lockFor(file)) {
            if (unique) {
                for (int i = 1; i < sortedEntries.size(); i++) {
                    if (IndexKeyCodec.compare(
                            sortedEntries.get(i - 1).keyBytes(),
                            sortedEntries.get(i).keyBytes(),
                            types
                    ) == 0) {
                        throw new IndexStoreException("duplicate key in unique index build");
                    }
                }
            }
            if (sortedEntries.isEmpty()) {
                writeMeta(file, -1, 0);
                return;
            }
            IndexSortedBuilder.build(this, file, types, sortedEntries);
        }
    }

    void rewriteLeafPage(String file, int pageId, List<BTreeLeafPage.LeafEntry> entries) {
        rewriteLeaf(file, pageId, entries);
    }

    void rewriteInternalPage(
            String file,
            int pageId,
            int leftChild,
            List<byte[]> keys,
            List<Integer> rightChildren
    ) {
        rewriteInternal(file, pageId, leftChild, keys, rightChildren);
    }

    int allocateIndexPage(String file, PageType type) {
        return allocatePage(file, type);
    }

    void writeIndexMeta(String file, int rootPageId, int height) {
        writeMeta(file, rootPageId, height);
    }

    void linkLeafPages(String file, int leftPageId, int rightPageId) {
        linkLeaves(file, leftPageId, rightPageId);
    }

    int pageSizeBytes() {
        return pageSize;
    }

    byte[] readFirstLeafKey(String file, int pageId, ColumnType[] types) {
        return withShared(file, pageId, frame -> {
            BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
            for (BTreeLeafPage.LeafEntry entry : page.liveEntries(types)) {
                return entry.keyBytes();
            }
            throw new IndexStoreException("empty leaf page: " + pageId);
        });
    }

    public void registerKeyTypes(String database, String table, IndexMetadata index, ColumnType[] keyTypes) {
        String key = IndexFiles.indexKey(database, table, index.name());
        keyTypesByIndex.put(key, keyTypes.clone());
        uniqueByIndex.put(key, index.unique());
    }

    public void loadKeyTypesFromCatalog(TableMetadata table) {
        for (IndexMetadata index : table.indexes()) {
            ColumnType[] types = index.columnIds().stream()
                    .map(id -> table.columns().stream()
                            .filter(c -> c.columnId().isPresent() && c.columnId().getAsInt() == id)
                            .findFirst()
                            .orElseThrow()
                            .type())
                    .toArray(ColumnType[]::new);
            registerKeyTypes(table.database(), table.name(), index, types);
        }
    }

    private SplitResult insertRecursive(
            String file,
            int pageId,
            int height,
            byte[] keyBytes,
            Rid rid,
            ColumnType[] types
    ) {
        if (height == 1) {
            return insertIntoLeafWithSplit(file, pageId, keyBytes, rid, types);
        }
        int child = withExclusive(file, pageId, frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            return page.childPageIdForKey(keyBytes, types);
        });
        SplitResult childSplit = insertRecursive(file, child, height - 1, keyBytes, rid, types);
        if (childSplit == null) {
            return null;
        }
        int slot = withExclusive(file, pageId, frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            int insertSlot = findInternalInsertSlot(page, childSplit.separatorKey(), types);
            if (fitsInternalInsert(page, childSplit.separatorKey())) {
                page.insertSeparator(insertSlot, childSplit.separatorKey(), childSplit.rightChild());
                bufferPool.markDirty(frame);
                return -1;
            }
            return insertSlot;
        });
        if (slot < 0) {
            return null;
        }
        return splitInternalPage(file, pageId, slot, childSplit.separatorKey(), childSplit.rightChild());
    }

    private SplitResult insertIntoLeafWithSplit(
            String file,
            int pageId,
            byte[] keyBytes,
            Rid rid,
            ColumnType[] types
    ) {
        Boolean fits = withExclusive(file, pageId, frame -> {
            BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
            if (!fitsLeafInsert(page, keyBytes)) {
                return false;
            }
            page.insertSorted(keyBytes, rid, types);
            bufferPool.markDirty(frame);
            return true;
        });
        if (fits) {
            return null;
        }
        List<BTreeLeafPage.LeafEntry> entries = withExclusive(file, pageId, frame -> {
            BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
            List<BTreeLeafPage.LeafEntry> live = new ArrayList<>(page.liveEntries(types));
            live.add(new BTreeLeafPage.LeafEntry(keyBytes, rid, IndexKeyCodec.decode(keyBytes, types)));
            live.sort(Comparator.comparing(BTreeLeafPage.LeafEntry::keyBytes, (a, b) -> IndexKeyCodec.compare(a, b, types)));
            return live;
        });
        int mid = entries.size() / 2;
        rewriteLeaf(file, pageId, entries.subList(0, mid));
        int rightPageId = allocatePage(file, PageType.INDEX_LEAF);
        rewriteLeaf(file, rightPageId, entries.subList(mid, entries.size()));
        linkLeaves(file, pageId, rightPageId);
        byte[] separator = entries.get(mid).keyBytes();
        return new SplitResult(pageId, separator, rightPageId);
    }

    private void insertIntoLeaf(String file, int pageId, byte[] keyBytes, Rid rid, ColumnType[] types) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeLeafPage.wrap(frame.data()).insertSorted(keyBytes, rid, types);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private SplitResult splitInternalPage(
            String file,
            int pageId,
            int insertSlot,
            byte[] separatorKey,
            int rightChild
    ) {
        SplitSnapshot snapshot = withExclusive(file, pageId, frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            List<byte[]> keys = new ArrayList<>();
            List<Integer> children = new ArrayList<>();
            children.add(page.leftChildPageId());
            for (int slot = 0; slot < page.slotCount(); slot++) {
                if (!page.isLive(slot)) {
                    continue;
                }
                keys.add(page.keyBytes(slot));
                children.add(page.rightChildAt(slot));
            }
            keys.add(insertSlot, separatorKey);
            children.add(insertSlot + 1, rightChild);
            return new SplitSnapshot(keys, children);
        });
        int mid = snapshot.keys().size() / 2;
        byte[] promote = snapshot.keys().get(mid);
        List<byte[]> leftKeys = new ArrayList<>(snapshot.keys().subList(0, mid));
        List<Integer> leftChildren = new ArrayList<>(snapshot.children().subList(0, mid + 1));
        List<byte[]> rightKeys = new ArrayList<>(snapshot.keys().subList(mid + 1, snapshot.keys().size()));
        List<Integer> rightChildren = new ArrayList<>(snapshot.children().subList(mid + 1, snapshot.children().size()));

        rewriteInternal(file, pageId, leftChildren.get(0), leftKeys, leftChildren.subList(1, leftChildren.size()));
        int rightPageId = allocatePage(file, PageType.INDEX_INTERNAL);
        rewriteInternal(file, rightPageId, rightChildren.get(0), rightKeys, rightChildren.subList(1, rightChildren.size()));
        return new SplitResult(pageId, promote, rightPageId);
    }

    private void rewriteLeaf(String file, int pageId, List<BTreeLeafPage.LeafEntry> entries) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeLeafPage fresh = BTreeLeafPage.createEmpty(pageId, pageSize);
            System.arraycopy(fresh.data(), 0, frame.data(), 0, pageSize);
            BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
            for (BTreeLeafPage.LeafEntry entry : entries) {
                page.insertAt(page.slotCount(), entry.keyBytes(), entry.rid());
            }
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private void rewriteInternal(
            String file,
            int pageId,
            int leftChild,
            List<byte[]> keys,
            List<Integer> rightChildren
    ) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeInternalPage fresh = BTreeInternalPage.createEmpty(pageId, pageSize);
            System.arraycopy(fresh.data(), 0, frame.data(), 0, pageSize);
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            page.setLeftChildPageId(leftChild);
            for (int i = 0; i < keys.size(); i++) {
                page.insertSeparator(i, keys.get(i), rightChildren.get(i));
            }
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private void writeNewInternalRoot(String file, int pageId, int leftChild, byte[] separator, int rightChild) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            page.setLeftChildPageId(leftChild);
            page.insertSeparator(0, separator, rightChild);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private void linkLeaves(String file, int leftPageId, int rightPageId) {
        withExclusive(file, leftPageId, (FrameCallback<Void>) frame -> {
            BTreeLeafPage.wrap(frame.data()).setNextLeafPageId(rightPageId);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private int findLeafPage(String file, int pageId, int height, byte[] keyBytes, ColumnType[] types) {
        int current = pageId;
        int level = height;
        while (level > 1) {
            current = withShared(file, current, frame -> {
                BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
                return page.childPageIdForKey(keyBytes, types);
            });
            level--;
        }
        return current;
    }

    private void deleteFromLeaf(String file, int pageId, byte[] keyBytes, Rid rid, ColumnType[] types) {
        removeFromLeaf(file, pageId, keyBytes, rid, types);
    }

    private boolean removeFromLeaf(String file, int pageId, byte[] keyBytes, Rid rid, ColumnType[] types) {
        return Boolean.TRUE.equals(withExclusive(file, pageId, frame -> {
            BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
            for (int slot = 0; slot < page.slotCount(); slot++) {
                if (!page.isLive(slot)) {
                    continue;
                }
                if (IndexKeyCodec.compare(page.keyBytes(slot), keyBytes, types) != 0) {
                    continue;
                }
                if (!page.ridAt(slot).equals(rid)) {
                    continue;
                }
                page.deleteSlot(slot);
                bufferPool.markDirty(frame);
                return true;
            }
            return false;
        }));
    }

    private int findLeftmostLeaf(String file, int pageId, int height) {
        int current = pageId;
        int level = height;
        while (level > 1) {
            current = withShared(file, current, frame -> {
                BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
                return page.leftChildPageId();
            });
            level--;
        }
        return current;
    }

    private void collectRangeOnLeafChain(
            String file,
            int startLeaf,
            IndexRange range,
            ColumnType[] types,
            List<Rid> out
    ) {
        int current = startLeaf;
        while (current >= 0) {
            int nextLeaf = withShared(file, current, frame -> {
                BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
                for (BTreeLeafPage.LeafEntry entry : page.liveEntries(types)) {
                    byte[] keyBytes = entry.keyBytes();
                    if (range.highKey() != null) {
                        byte[] highBytes = IndexKeyCodec.encodePrefix(
                                padKey(range.highKey(), types),
                                types,
                                range.prefixColumns()
                        );
                        int highCmp = IndexKeyCodec.comparePrefix(
                                keyBytes,
                                highBytes,
                                types,
                                range.prefixColumns()
                        );
                        if (highCmp > 0 || (highCmp == 0 && !range.highInclusive())) {
                            return -2;
                        }
                    }
                    if (keyWithinRange(keyBytes, range, types)) {
                        out.add(entry.rid());
                    }
                }
                return page.nextLeafPageId();
            });
            if (nextLeaf == -2) {
                break;
            }
            current = nextLeaf;
        }
    }

    private static boolean keyWithinRange(byte[] keyBytes, IndexRange range, ColumnType[] types) {
        int prefix = range.prefixColumns();
        if (range.lowKey() != null) {
            byte[] lowBytes = IndexKeyCodec.encodePrefix(padKey(range.lowKey(), types), types, prefix);
            int lowCmp = IndexKeyCodec.comparePrefix(keyBytes, lowBytes, types, prefix);
            if (lowCmp < 0 || (lowCmp == 0 && !range.lowInclusive())) {
                return false;
            }
        }
        if (range.highKey() != null) {
            byte[] highBytes = IndexKeyCodec.encodePrefix(padKey(range.highKey(), types), types, prefix);
            int highCmp = IndexKeyCodec.comparePrefix(keyBytes, highBytes, types, prefix);
            if (highCmp > 0 || (highCmp == 0 && !range.highInclusive())) {
                return false;
            }
        }
        return true;
    }

    private static Object[] padKey(Object[] key, ColumnType[] types) {
        Object[] full = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            full[i] = i < key.length ? key[i] : null;
        }
        return full;
    }

    private void enforceUniqueOnInsert(
            String file,
            String database,
            String table,
            String indexName,
            byte[] keyBytes,
            ColumnType[] types
    ) {
        if (!isUnique(database, table, indexName)) {
            return;
        }
        MetaView meta = readMeta(file);
        if (meta.rootPageId() < 0) {
            return;
        }
        int leafPageId = findLeafPage(file, meta.rootPageId(), meta.height(), keyBytes, types);
        List<Rid> matches = new ArrayList<>();
        collectMatchesOnLeaf(file, leafPageId, keyBytes, types, matches);
        if (!matches.isEmpty()) {
            throw new IndexStoreException("duplicate key in unique index: " + indexName);
        }
    }

    private boolean isUnique(String database, String table, String indexName) {
        return Boolean.TRUE.equals(uniqueByIndex.get(IndexFiles.indexKey(database, table, indexName)));
    }

    private byte[] sampleKeyBytes(ColumnType[] types) {
        Object[] sample = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            sample[i] = switch (types[i]) {
                case INT -> 0;
                case BOOLEAN -> false;
                case VARCHAR -> "x";
            };
        }
        return IndexKeyCodec.encode(sample, types);
    }

    private int minLeafEntries(ColumnType[] types) {
        byte[] sample = sampleKeyBytes(types);
        BTreeLeafPage probe = BTreeLeafPage.createEmpty(0, pageSize);
        int count = 0;
        while (fitsLeafInsert(probe, sample)) {
            probe.insertAt(probe.slotCount(), sample, new Rid(0, count++));
        }
        return Math.max(1, count / 2);
    }

    private void rebalanceAfterLeafDelete(
            String file,
            int rootPageId,
            int height,
            int leafPageId,
            ColumnType[] types
    ) {
        int live = liveLeafCount(file, leafPageId, types);
        int min = minLeafEntries(types);
        if (live >= min) {
            return;
        }
        int rightSibling = withShared(file, leafPageId, frame -> BTreeLeafPage.wrap(frame.data()).nextLeafPageId());
        if (rightSibling >= 0 && tryBorrowFromRightLeaf(file, rootPageId, height, leafPageId, rightSibling, types, min)) {
            return;
        }
        Integer leftSibling = findLeftLeafSibling(file, rootPageId, height, leafPageId);
        if (leftSibling != null && tryBorrowFromRightLeaf(file, rootPageId, height, leftSibling, leafPageId, types, min)) {
            return;
        }
        if (rightSibling >= 0) {
            mergeLeaves(file, rootPageId, height, leafPageId, rightSibling, types);
            return;
        }
        if (leftSibling != null) {
            mergeLeaves(file, rootPageId, height, leftSibling, leafPageId, types);
        }
    }

    private int liveLeafCount(String file, int leafPageId, ColumnType[] types) {
        return withShared(file, leafPageId, frame -> BTreeLeafPage.wrap(frame.data()).liveEntries(types).size());
    }

    private Integer findLeftLeafSibling(String file, int rootPageId, int height, int leafPageId) {
        List<Integer> leaves = new ArrayList<>();
        collectLeafChain(file, findLeftmostLeaf(file, rootPageId, height), leaves);
        for (int i = 1; i < leaves.size(); i++) {
            if (leaves.get(i) == leafPageId) {
                return leaves.get(i - 1);
            }
        }
        return null;
    }

    private void collectLeafChain(String file, int startLeaf, List<Integer> out) {
        int current = startLeaf;
        while (current >= 0) {
            out.add(current);
            current = withShared(file, current, frame -> BTreeLeafPage.wrap(frame.data()).nextLeafPageId());
        }
    }

    private boolean tryBorrowFromRightLeaf(
            String file,
            int rootPageId,
            int height,
            int leftLeaf,
            int rightLeaf,
            ColumnType[] types,
            int min
    ) {
        List<BTreeLeafPage.LeafEntry> leftEntries = readLiveLeafEntries(file, leftLeaf, types);
        List<BTreeLeafPage.LeafEntry> rightEntries = readLiveLeafEntries(file, rightLeaf, types);
        if (leftEntries.size() >= min || rightEntries.size() <= min) {
            return false;
        }
        BTreeLeafPage.LeafEntry moved = rightEntries.remove(0);
        leftEntries.add(moved);
        rewriteLeaf(file, leftLeaf, leftEntries);
        rewriteLeaf(file, rightLeaf, rightEntries);
        updateParentSeparatorForRightLeaf(file, rootPageId, height, rightLeaf, rightEntries, types);
        return true;
    }

    private List<BTreeLeafPage.LeafEntry> readLiveLeafEntries(String file, int leafPageId, ColumnType[] types) {
        return withShared(file, leafPageId, frame -> new ArrayList<>(BTreeLeafPage.wrap(frame.data()).liveEntries(types)));
    }

    private void updateParentSeparatorForRightLeaf(
            String file,
            int rootPageId,
            int height,
            int rightLeaf,
            List<BTreeLeafPage.LeafEntry> rightEntries,
            ColumnType[] types
    ) {
        if (height == 1 || rightEntries.isEmpty()) {
            return;
        }
        byte[] separator = rightEntries.get(0).keyBytes();
        ParentRef parent = findParentOfLeaf(file, rootPageId, height, rightLeaf);
        if (parent == null) {
            return;
        }
        withExclusive(file, parent.pageId(), (FrameCallback<Void>) frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            if (parent.slot() < 0) {
                return null;
            }
            page.replaceSeparatorKey(parent.slot(), separator);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private void mergeLeaves(
            String file,
            int rootPageId,
            int height,
            int leftLeaf,
            int rightLeaf,
            ColumnType[] types
    ) {
        List<BTreeLeafPage.LeafEntry> merged = readLiveLeafEntries(file, leftLeaf, types);
        merged.addAll(readLiveLeafEntries(file, rightLeaf, types));
        int nextRight = withShared(file, rightLeaf, frame -> BTreeLeafPage.wrap(frame.data()).nextLeafPageId());
        rewriteLeaf(file, leftLeaf, merged);
        withExclusive(file, leftLeaf, (FrameCallback<Void>) frame -> {
            BTreeLeafPage.wrap(frame.data()).setNextLeafPageId(nextRight);
            bufferPool.markDirty(frame);
            return null;
        });
        clearLeafPage(file, rightLeaf);
        removeChildFromParent(file, rootPageId, height, rightLeaf, types);
    }

    private void clearLeafPage(String file, int pageId) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeLeafPage fresh = BTreeLeafPage.createEmpty(pageId, pageSize);
            System.arraycopy(fresh.data(), 0, frame.data(), 0, pageSize);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private ParentRef findParentOfLeaf(String file, int rootPageId, int height, int leafPageId) {
        if (height == 1) {
            return null;
        }
        return findParentOfPage(file, rootPageId, height, leafPageId);
    }

    private void removeChildFromParent(
            String file,
            int rootPageId,
            int height,
            int childPageId,
            ColumnType[] types
    ) {
        if (height == 1) {
            return;
        }
        ParentRef parent = findParentOfLeaf(file, rootPageId, height, childPageId);
        if (parent == null) {
            return;
        }
        withExclusive(file, parent.pageId(), (FrameCallback<Void>) frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            if (parent.slot() < 0) {
                int newLeft = page.rightChildAt(0);
                page.deleteSlot(0);
                page.setLeftChildPageId(newLeft);
            } else {
                page.deleteSlot(parent.slot());
            }
            bufferPool.markDirty(frame);
            return null;
        });
        rebalanceInternalAfterDelete(file, rootPageId, height, parent.pageId(), types);
    }

    /**
     * Rebalance an internal node after a child was removed and the node may have underflowed.
     * Pattern mirrors leaf rebalance: try borrow-right, borrow-left, merge-right, merge-left.
     * After a merge the parent may also underflow — cascade recursively up the tree.
     * Root shrink is handled by {@link #maybeShrinkRoot} after the recursive chain.
     */
    private void rebalanceInternalAfterDelete(
            String file,
            int rootPageId,
            int height,
            int internalPageId,
            ColumnType[] types
    ) {
        if (height <= 1) {
            return;
        }
        int live = withShared(file, internalPageId, frame -> BTreeInternalPage.wrap(frame.data()).liveSlotCount());
        // An internal node needs at least 1 separator (2 children) to be useful.
        int min = 1;
        if (live >= min) {
            return;
        }
        if (internalPageId == rootPageId) {
            // Root with 0 separators — maybeShrinkRoot will handle height reduction.
            return;
        }
        ParentRef parent = findParentOfInternal(file, rootPageId, height, internalPageId);
        if (parent == null) {
            return;
        }
        // Find siblings via parent.
        InternalSiblings siblings = findInternalSiblings(file, parent.pageId(), internalPageId);
        // Try borrow from right sibling.
        if (siblings.rightPageId >= 0
                && tryBorrowFromRightInternal(file, parent, internalPageId, siblings.rightPageId, types)) {
            return;
        }
        // Try borrow from left sibling.
        if (siblings.leftPageId >= 0
                && tryBorrowFromLeftInternal(file, parent, siblings.leftPageId, internalPageId, types)) {
            return;
        }
        // Merge with right sibling.
        if (siblings.rightPageId >= 0) {
            mergeInternalNodes(file, rootPageId, height, parent, internalPageId, siblings.rightPageId, types);
            return;
        }
        // Merge with left sibling (left absorbs current).
        if (siblings.leftPageId >= 0) {
            mergeInternalNodes(file, rootPageId, height, parent, siblings.leftPageId, internalPageId, types);
        }
    }

    /**
     * Borrow the leftmost separator+child from the right internal sibling.
     * Pull parent separator down into the underflowing node, push right's first key up.
     */
    private boolean tryBorrowFromRightInternal(
            String file, ParentRef parent,
            int leftPageId, int rightPageId, ColumnType[] types) {
        List<BTreeInternalPage.InternalEntry> rightEntries = readLiveInternalEntries(file, rightPageId);
        if (rightEntries.size() <= 1) {
            return false; // right sibling would underflow
        }
        // The separator in the parent that sits between left and right children.
        int parentSepSlot = findSeparatorSlotBetween(file, parent.pageId(), leftPageId, rightPageId);
        if (parentSepSlot < 0) {
            return false;
        }
        byte[] parentSepKey = withShared(file, parent.pageId(),
                frame -> BTreeInternalPage.wrap(frame.data()).keyBytes(parentSepSlot));
        // Right sibling's leftChild becomes the new rightChild of the pulled-down separator.
        int rightLeftChild = withShared(file, rightPageId,
                frame -> BTreeInternalPage.wrap(frame.data()).leftChildPageId());
        // Pull parent separator down into left node.
        List<BTreeInternalPage.InternalEntry> leftEntries = readLiveInternalEntries(file, leftPageId);
        int leftChild = withShared(file, leftPageId,
                frame -> BTreeInternalPage.wrap(frame.data()).leftChildPageId());
        leftEntries.add(new BTreeInternalPage.InternalEntry(parentSepKey, rightLeftChild));
        rewriteInternalPage(file, leftPageId, leftChild, leftEntries);
        // Push right's first key up to parent.
        BTreeInternalPage.InternalEntry borrowed = rightEntries.remove(0);
        withExclusive(file, parent.pageId(), (FrameCallback<Void>) frame -> {
            BTreeInternalPage.wrap(frame.data()).replaceSeparatorKey(parentSepSlot, borrowed.keyBytes());
            bufferPool.markDirty(frame);
            return null;
        });
        // Right's new leftChild is the rightChild of the borrowed entry.
        rewriteInternalPage(file, rightPageId, borrowed.rightChildPageId(), rightEntries);
        return true;
    }

    /**
     * Borrow the rightmost separator+child from the left internal sibling.
     */
    private boolean tryBorrowFromLeftInternal(
            String file, ParentRef parent,
            int leftPageId, int rightPageId, ColumnType[] types) {
        List<BTreeInternalPage.InternalEntry> leftEntries = readLiveInternalEntries(file, leftPageId);
        if (leftEntries.size() <= 1) {
            return false;
        }
        int parentSepSlot = findSeparatorSlotBetween(file, parent.pageId(), leftPageId, rightPageId);
        if (parentSepSlot < 0) {
            return false;
        }
        byte[] parentSepKey = withShared(file, parent.pageId(),
                frame -> BTreeInternalPage.wrap(frame.data()).keyBytes(parentSepSlot));
        int rightLeftChild = withShared(file, rightPageId,
                frame -> BTreeInternalPage.wrap(frame.data()).leftChildPageId());
        // Take last entry from left.
        BTreeInternalPage.InternalEntry borrowed = leftEntries.remove(leftEntries.size() - 1);
        int leftChild = withShared(file, leftPageId,
                frame -> BTreeInternalPage.wrap(frame.data()).leftChildPageId());
        rewriteInternalPage(file, leftPageId, leftChild, leftEntries);
        // Pull parent separator down as first entry in right, with rightLeftChild as its rightChild.
        List<BTreeInternalPage.InternalEntry> rightEntries = readLiveInternalEntries(file, rightPageId);
        rightEntries.add(0, new BTreeInternalPage.InternalEntry(parentSepKey, rightLeftChild));
        // New leftChild of right = rightChild of borrowed entry.
        rewriteInternalPage(file, rightPageId, borrowed.rightChildPageId(), rightEntries);
        // Push borrowed key up to parent.
        withExclusive(file, parent.pageId(), (FrameCallback<Void>) frame -> {
            BTreeInternalPage.wrap(frame.data()).replaceSeparatorKey(parentSepSlot, borrowed.keyBytes());
            bufferPool.markDirty(frame);
            return null;
        });
        return true;
    }

    /**
     * Merge right internal node into left. Pull parent separator down as middle key,
     * concatenate children. Then remove the right child pointer from parent and cascade.
     */
    private void mergeInternalNodes(
            String file, int rootPageId, int height, ParentRef parent,
            int leftPageId, int rightPageId, ColumnType[] types) {
        int parentSepSlot = findSeparatorSlotBetween(file, parent.pageId(), leftPageId, rightPageId);
        if (parentSepSlot < 0) {
            return;
        }
        byte[] parentSepKey = withShared(file, parent.pageId(),
                frame -> BTreeInternalPage.wrap(frame.data()).keyBytes(parentSepSlot));
        int leftChild = withShared(file, leftPageId,
                frame -> BTreeInternalPage.wrap(frame.data()).leftChildPageId());
        int rightLeftChild = withShared(file, rightPageId,
                frame -> BTreeInternalPage.wrap(frame.data()).leftChildPageId());
        List<BTreeInternalPage.InternalEntry> merged = readLiveInternalEntries(file, leftPageId);
        // Parent separator becomes a key bridging leftPageId's last child to rightPageId's leftChild.
        merged.add(new BTreeInternalPage.InternalEntry(parentSepKey, rightLeftChild));
        merged.addAll(readLiveInternalEntries(file, rightPageId));
        rewriteInternalPage(file, leftPageId, leftChild, merged);
        // Clear the right page.
        clearInternalPage(file, rightPageId);
        // Remove the separator and right child pointer from parent.
        withExclusive(file, parent.pageId(), (FrameCallback<Void>) frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            if (parentSepSlot < 0) {
                // The right was the leftChild — should not happen in normal flow.
                return null;
            }
            page.deleteSlot(parentSepSlot);
            bufferPool.markDirty(frame);
            return null;
        });
        // Parent may now underflow — cascade.
        rebalanceInternalAfterDelete(file, rootPageId, height, parent.pageId(), types);
    }

    /** Find the separator slot in an internal parent that sits between two child page ids. */
    private int findSeparatorSlotBetween(String file, int parentPageId, int leftChild, int rightChild) {
        return withShared(file, parentPageId, frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            // Walk the parent: leftChildPageId, then slot0→rightChild, slot1→rightChild, ...
            int prevChild = page.leftChildPageId();
            for (int slot = 0; slot < page.slotCount(); slot++) {
                if (!page.isLive(slot)) {
                    continue;
                }
                int rc = page.rightChildAt(slot);
                if (prevChild == leftChild && rc == rightChild) {
                    return slot;
                }
                prevChild = rc;
            }
            return -1;
        });
    }

    /** Read live entries from an internal page. */
    private List<BTreeInternalPage.InternalEntry> readLiveInternalEntries(String file, int pageId) {
        return withShared(file, pageId, frame ->
                new ArrayList<>(BTreeInternalPage.wrap(frame.data()).liveEntries(null)));
    }

    /** Rewrite an internal page from scratch with the given leftChild and entries. */
    private void rewriteInternalPage(String file, int pageId, int leftChild,
                                     List<BTreeInternalPage.InternalEntry> entries) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeInternalPage fresh = BTreeInternalPage.createEmpty(pageId, pageSize);
            System.arraycopy(fresh.data(), 0, frame.data(), 0, pageSize);
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            page.setLeftChildPageId(leftChild);
            for (int i = 0; i < entries.size(); i++) {
                BTreeInternalPage.InternalEntry entry = entries.get(i);
                page.insertSeparator(i, entry.keyBytes(), entry.rightChildPageId());
            }
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private void clearInternalPage(String file, int pageId) {
        withExclusive(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeInternalPage fresh = BTreeInternalPage.createEmpty(pageId, pageSize);
            System.arraycopy(fresh.data(), 0, frame.data(), 0, pageSize);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    /** Left and right sibling page ids for an internal node under a parent. */
    private InternalSiblings findInternalSiblings(String file, int parentPageId, int targetPageId) {
        return withShared(file, parentPageId, frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            int prevChild = -1;
            int current = page.leftChildPageId();
            for (int slot = 0; slot < page.slotCount(); slot++) {
                if (!page.isLive(slot)) {
                    continue;
                }
                int nextChild = page.rightChildAt(slot);
                if (current == targetPageId) {
                    return new InternalSiblings(prevChild, nextChild);
                }
                prevChild = current;
                current = nextChild;
            }
            // target is the last child
            if (current == targetPageId) {
                return new InternalSiblings(prevChild, -1);
            }
            return new InternalSiblings(-1, -1);
        });
    }

    private record InternalSiblings(int leftPageId, int rightPageId) {
    }

    private ParentRef findParentOfInternal(String file, int rootPageId, int height, int internalPageId) {
        if (height <= 1 || internalPageId == rootPageId) {
            return null;
        }
        return findParentOfPage(file, rootPageId, height, internalPageId);
    }

    private ParentRef findParentOfPage(String file, int pageId, int height, int targetPageId) {
        if (height == 1) {
            return null;
        }
        return withShared(file, pageId, frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            int left = page.leftChildPageId();
            if (left == targetPageId) {
                return new ParentRef(pageId, -1);
            }
            ParentRef inLeft = findParentOfPage(file, left, height - 1, targetPageId);
            if (inLeft != null) {
                return inLeft;
            }
            for (int slot = 0; slot < page.slotCount(); slot++) {
                if (!page.isLive(slot)) {
                    continue;
                }
                int child = page.rightChildAt(slot);
                if (child == targetPageId) {
                    return new ParentRef(pageId, slot);
                }
                ParentRef found = findParentOfPage(file, child, height - 1, targetPageId);
                if (found != null) {
                    return found;
                }
            }
            return null;
        });
    }

    private void maybeShrinkRoot(String file, ColumnType[] types) {
        MetaView meta = readMeta(file);
        if (meta.rootPageId() < 0) {
            return;
        }
        if (meta.height() == 1) {
            int live = liveLeafCount(file, meta.rootPageId(), types);
            if (live == 0) {
                writeMeta(file, -1, 0);
            }
            return;
        }
        int childCount = withShared(file, meta.rootPageId(), frame -> {
            BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
            return page.liveSlotCount() + 1;
        });
        if (childCount == 1) {
            int newRoot = withShared(file, meta.rootPageId(), frame -> {
                BTreeInternalPage page = BTreeInternalPage.wrap(frame.data());
                return page.leftChildPageId();
            });
            writeMeta(file, newRoot, meta.height() - 1);
        }
    }

    private void collectMatchesOnLeaf(
            String file,
            int pageId,
            byte[] keyBytes,
            ColumnType[] types,
            List<Rid> out
    ) {
        withShared(file, pageId, (FrameCallback<Void>) frame -> {
            BTreeLeafPage page = BTreeLeafPage.wrap(frame.data());
            for (BTreeLeafPage.LeafEntry entry : page.liveEntries(types)) {
                if (IndexKeyCodec.compare(entry.keyBytes(), keyBytes, types) == 0) {
                    out.add(entry.rid());
                }
            }
            return null;
        });
    }

    private int allocatePage(String file, PageType type) {
        BufferFrame frame = bufferPool.newPage(file, type);
        try {
            return frame.pageId().pageId();
        } finally {
            bufferPool.unpin(frame);
        }
    }

    private MetaView readMeta(String file) {
        return withShared(file, 0, frame -> {
            IndexMetaPage meta = IndexMetaPage.wrap(frame.data());
            int stamped = meta.pageSize();
            // Older images may have zero in the new field — treat as "unset" and require a write.
            if (stamped == 0) {
                throw new IndexStoreException(
                        "index file " + file + " missing stamped PAGE_SIZE; recreate the index"
                );
            }
            if (stamped != pageSize) {
                throw new IndexStoreException(
                        "index file " + file + " stamped PAGE_SIZE " + stamped
                                + " does not match server PAGE_SIZE " + pageSize
                );
            }
            return new MetaView(meta.rootPageId(), meta.height());
        });
    }

    private void writeMeta(String file, int rootPageId, int height) {
        withExclusive(file, 0, (FrameCallback<Void>) frame -> {
            IndexMetaPage.wrap(frame.data()).setRoot(rootPageId, height);
            bufferPool.markDirty(frame);
            return null;
        });
    }

    private boolean fitsLeafInsert(BTreeLeafPage page, byte[] keyBytes) {
        return page.freeSpace() >= PageLayout.SLOT_SIZE + keyBytes.length + IndexPageLayout.RID_BYTES;
    }

    private boolean fitsInternalInsert(BTreeInternalPage page, byte[] keyBytes) {
        return page.freeSpace() >= PageLayout.SLOT_SIZE + keyBytes.length + IndexPageLayout.CHILD_BYTES;
    }

    private int findInternalInsertSlot(BTreeInternalPage page, byte[] keyBytes, ColumnType[] types) {
        for (int slot = 0; slot < page.slotCount(); slot++) {
            if (!page.isLive(slot)) {
                continue;
            }
            if (IndexKeyCodec.compare(keyBytes, page.keyBytes(slot), types) < 0) {
                return slot;
            }
        }
        return page.slotCount();
    }

    private <T> T withExclusive(String file, int pageId, FrameCallback<T> action) {
        BufferFrame frame = bufferPool.pin(new PageId(file, pageId));
        bufferPool.latchExclusive(frame);
        try {
            return action.run(frame);
        } finally {
            bufferPool.unlatch(frame);
            bufferPool.unpin(frame);
        }
    }

    private <T> T withShared(String file, int pageId, FrameCallback<T> action) {
        BufferFrame frame = bufferPool.pin(new PageId(file, pageId));
        bufferPool.latchShared(frame);
        try {
            return action.run(frame);
        } finally {
            bufferPool.unlatch(frame);
            bufferPool.unpin(frame);
        }
    }

    private ColumnType[] requireKeyTypes(String database, String table, String indexName) {
        ColumnType[] types = keyTypesOrNull(database, table, indexName);
        if (types == null) {
            throw new IndexStoreException("unknown index key types: " + database + "." + table + "." + indexName);
        }
        return types;
    }

    private ColumnType[] keyTypesOrNull(String database, String table, String indexName) {
        return keyTypesByIndex.get(IndexFiles.indexKey(database, table, indexName));
    }

    private static Object lockFor(String file) {
        return file.intern();
    }

    @FunctionalInterface
    private interface FrameCallback<T> {
        T run(BufferFrame frame);
    }

    private record ParentRef(int pageId, int slot) {
    }

    private record MetaView(int rootPageId, int height) {
    }

    private record SplitResult(int leftChild, byte[] separatorKey, int rightChild) {
    }

    private record SplitSnapshot(List<byte[]> keys, List<Integer> children) {
    }
}
