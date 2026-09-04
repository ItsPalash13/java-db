# Page graph (`.idx` → `.ibd`)

Offline Python tool that reads JavaDatabase page files and writes an **interactive HTML** graph.

- **Left:** B+ tree from a `.idx` (META → INTERNAL → LEAF) plus HEAP nodes for pages referenced by leaf Rids.
- **Right:** Click a **LEAF**, then an **entry row**, to open that heap page and highlight the Rid slot / row.

Disk image only — run `CHECKPOINT` or stop the server first if you need a consistent dump.

## Requirements

Python 3.10+ (stdlib only). The HTML loads [vis-network](https://visjs.github.io/vis-network/docs/network/) from a CDN when you open it in a browser.

## Usage

From the repo root:

```bash
python tools/page-graph/page_graph.py --idx data/shop/users/idx_orders_user.idx
```

Sibling `catalog.json` and `*.ibd` are inferred when present. Explicit flags:

```bash
python tools/page-graph/page_graph.py \
  --idx data/shop/users/idx_orders_user.idx \
  --ibd data/shop/users/users.ibd \
  --catalog data/shop/users/catalog.json \
  --out out/page-graph/idx_orders_user.html
```

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

Key and row types come from `catalog.json` (index `columnIds` → key; all table columns → heap decode).
