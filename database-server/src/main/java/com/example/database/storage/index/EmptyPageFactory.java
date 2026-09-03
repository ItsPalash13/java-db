package com.example.database.storage.index;

import com.example.database.storage.page.HeapPage;
import com.example.database.storage.page.PageType;

/**
 * Factory for empty on-disk page images by {@link PageType}.
 */
public final class EmptyPageFactory {

    private EmptyPageFactory() {
    }

    public static byte[] emptyPage(int pageId, int pageSize, PageType type) {
        return switch (type) {
            case HEAP -> HeapPage.createEmpty(pageId, pageSize).toBytes();
            case INDEX_META -> IndexMetaPage.createEmpty(pageId, pageSize).toBytes();
            case INDEX_LEAF -> BTreeLeafPage.createEmpty(pageId, pageSize).data();
            case INDEX_INTERNAL -> BTreeInternalPage.createEmpty(pageId, pageSize).data();
        };
    }
}
