# Page graph (`.idx` → `.ibd`)

Offline Python tool that reads JavaDatabase page files and writes an **interactive HTML** graph.

- **Left:** B+ tree — internal root fans out to leaf children (solid); dashed `nextLeaf` links siblings left→right. HEAP nodes appear only when you follow a Rid.
- **Right:** Click a **LEAF**, then a **key row**, to see the **selected heap row** (all columns) plus heap page chips (page id, free bytes, slots, live) and the full page table.
- **Tabs (top-right):** one tab per `.idx` when you pass a table directory.

Disk image only — run `CHECKPOINT` or stop the server first if you need a consistent dump.

## Requirements

Python 3.10+ (stdlib only). The HTML loads [vis-network](https://visjs.github.io/vis-network/docs/network/) from a CDN when you open it in a browser.

## Usage

**All indexes for a table** (recommended):

```bash
python tools/page-graph/page_graph.py --table-dir data/shop/users --out out/page-graph/users.html
```

Discovers `catalog.json`, `*.ibd`, and every `*.idx` under that directory.

**Single index:**

```bash
python tools/page-graph/page_graph.py --idx data/shop/users/idx_users_name.idx
```

Sibling `catalog.json` and `*.ibd` are inferred when present.

Then open the HTML file in a browser.

## Layout

Matches the Java codecs:

| Constant | Value |
|----------|--------|
| Page size | 16 KiB (or `PAGE_SIZE` in `server.env`) |
| Magic | `0x4A44` (`JD`) |
| `.ibd` page 0 | `HEAP_META` — stamped page size; rows from page 1 |
| `.idx` page 0 | `INDEX_META` — root, height, stamped page size |
| Leaf entry | key bytes + Rid `(pageId:i32, slotId:i32)` |
| Internal entry | separator key + child `pageId:i32` |

Key and row types come from `catalog.json`.
