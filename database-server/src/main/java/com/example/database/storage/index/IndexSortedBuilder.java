package com.example.database.storage.index;

import com.example.database.storage.catalog.ColumnType;
import com.example.database.storage.page.PageLayout;
import com.example.database.storage.page.PageType;
import com.example.database.storage.page.Rid;

import java.util.ArrayList;
import java.util.List;

/**
 * Sort-build CREATE INDEX: pack sorted leaf entries left-to-right, link siblings,
 * then build internal levels bottom-up until one root remains.
 */
final class IndexSortedBuilder {

    private IndexSortedBuilder() {
    }

    static void build(
            FileIndexStore store,
            String file,
            ColumnType[] types,
            List<BTreeLeafPage.LeafEntry> sortedEntries
    ) {
        byte[] sampleKey = sortedEntries.get(0).keyBytes();
        List<List<BTreeLeafPage.LeafEntry>> leafChunks = packLeaves(store, sampleKey, sortedEntries);
        List<Integer> leafPageIds = new ArrayList<>(leafChunks.size());
        for (List<BTreeLeafPage.LeafEntry> chunk : leafChunks) {
            int pageId = store.allocateIndexPage(file, PageType.INDEX_LEAF);
            store.rewriteLeafPage(file, pageId, chunk);
            leafPageIds.add(pageId);
        }
        for (int i = 0; i + 1 < leafPageIds.size(); i++) {
            store.linkLeafPages(file, leafPageIds.get(i), leafPageIds.get(i + 1));
        }
        if (leafPageIds.size() == 1) {
            store.writeIndexMeta(file, leafPageIds.get(0), 1);
            return;
        }
        List<Integer> currentLevel = leafPageIds;
        int height = 1;
        while (currentLevel.size() > 1) {
            List<Integer> parentLevel = new ArrayList<>();
            int maxChildren = maxInternalChildren(store, sampleKey);
            for (int start = 0; start < currentLevel.size(); start += maxChildren) {
                int end = Math.min(start + maxChildren, currentLevel.size());
                List<Integer> children = currentLevel.subList(start, end);
                List<byte[]> keys = new ArrayList<>();
                List<Integer> rightChildren = new ArrayList<>();
                for (int i = 1; i < children.size(); i++) {
                    int childPageId = children.get(i);
                    byte[] separator = firstKeyOnLeaf(store, file, childPageId, types);
                    keys.add(separator);
                    rightChildren.add(childPageId);
                }
                int internalPageId = store.allocateIndexPage(file, PageType.INDEX_INTERNAL);
                store.rewriteInternalPage(file, internalPageId, children.get(0), keys, rightChildren);
                parentLevel.add(internalPageId);
            }
            currentLevel = parentLevel;
            height++;
        }
        store.writeIndexMeta(file, currentLevel.get(0), height);
    }

    private static List<List<BTreeLeafPage.LeafEntry>> packLeaves(
            FileIndexStore store,
            byte[] sampleKey,
            List<BTreeLeafPage.LeafEntry> sortedEntries
    ) {
        List<List<BTreeLeafPage.LeafEntry>> chunks = new ArrayList<>();
        List<BTreeLeafPage.LeafEntry> current = new ArrayList<>();
        BTreeLeafPage probe = BTreeLeafPage.createEmpty(0, store.pageSizeBytes());
        for (BTreeLeafPage.LeafEntry entry : sortedEntries) {
            if (!fitsLeafInsert(probe, entry.keyBytes())) {
                if (!current.isEmpty()) {
                    chunks.add(current);
                    current = new ArrayList<>();
                    probe = BTreeLeafPage.createEmpty(0, store.pageSizeBytes());
                }
            }
            probe.insertAt(probe.slotCount(), entry.keyBytes(), entry.rid());
            current.add(entry);
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }

    private static boolean fitsLeafInsert(BTreeLeafPage page, byte[] keyBytes) {
        return page.freeSpace() >= PageLayout.SLOT_SIZE + keyBytes.length + IndexPageLayout.RID_BYTES;
    }

    private static int maxInternalChildren(FileIndexStore store, byte[] sampleKey) {
        BTreeInternalPage probe = BTreeInternalPage.createEmpty(0, store.pageSizeBytes());
        int children = 1;
        while (probe.freeSpace() >= PageLayout.SLOT_SIZE + sampleKey.length + IndexPageLayout.CHILD_BYTES) {
            probe.insertSeparator(probe.slotCount(), sampleKey, children);
            children++;
        }
        return Math.max(2, children - 1);
    }

    private static byte[] firstKeyOnLeaf(FileIndexStore store, String file, int pageId, ColumnType[] types) {
        return store.readFirstLeafKey(file, pageId, types);
    }
}
