#!/usr/bin/env python3
"""
Offline visualizer for JavaDatabase .idx / .ibd pages.

Reads slotted pages (size from meta stamp or --page-size), builds B+ tree
graph(s), and writes a self-contained interactive HTML file.

Use --idx for one index, or --table-dir to load every .idx under a table
folder (shared heap, index tabs). Click a leaf entry for the heap row card.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path
from typing import Any

DEFAULT_PAGE_SIZE = 16 * 1024
MAGIC = 0x4A44
HEADER_SIZE = 24
SLOT_SIZE = 4

OFF_MAGIC = 0
OFF_PAGE_TYPE = 2
OFF_PAGE_ID = 4
OFF_SLOT_COUNT = 8
OFF_LOWER = 10
OFF_UPPER = 12
OFF_LSN_RESERVED = 16

OFF_META_ROOT = OFF_LSN_RESERVED
OFF_META_HEIGHT = OFF_LSN_RESERVED + 4
OFF_META_PAGE_SIZE = HEADER_SIZE  # 24
OFF_META_KEY_PADDING = HEADER_SIZE + 4  # 28
OFF_HEAP_META_PAGE_SIZE = OFF_LSN_RESERVED
OFF_LEAF_NEXT = OFF_LSN_RESERVED
OFF_INTERNAL_LEFT = OFF_LSN_RESERVED

RID_BYTES = 8
CHILD_BYTES = 4

PAGE_HEAP = 1
PAGE_INDEX_META = 2
PAGE_INDEX_LEAF = 3
PAGE_INDEX_INTERNAL = 4
PAGE_HEAP_META = 5

TYPE_NAMES = {
    PAGE_HEAP: "HEAP",
    PAGE_INDEX_META: "META",
    PAGE_INDEX_LEAF: "LEAF",
    PAGE_INDEX_INTERNAL: "INTERNAL",
    PAGE_HEAP_META: "HEAP_META",
}


class PageError(Exception):
    pass


def u16(data: bytes, off: int) -> int:
    return struct.unpack_from(">H", data, off)[0]


def i32(data: bytes, off: int) -> int:
    return struct.unpack_from(">i", data, off)[0]


def i64(data: bytes, off: int) -> int:
    return struct.unpack_from(">q", data, off)[0]


def null_bitmap_bytes(column_count: int) -> int:
    return (column_count + 7) // 8


def read_pages(path: Path, page_size: int) -> list[bytes]:
    raw = path.read_bytes()
    if len(raw) % page_size != 0:
        raise PageError(f"{path}: size {len(raw)} is not a multiple of {page_size}")
    return [raw[i : i + page_size] for i in range(0, len(raw), page_size)]


def detect_page_size(path: Path) -> int:
    """Read PAGE_SIZE stamp from page-0 meta (works before knowing the page length)."""
    raw = path.read_bytes()
    if len(raw) < HEADER_SIZE + 4:
        raise PageError(f"{path}: too short for a meta page")
    if u16(raw, OFF_MAGIC) != MAGIC:
        raise PageError(f"{path}: bad magic")
    ptype = raw[OFF_PAGE_TYPE]
    if ptype == PAGE_INDEX_META:
        stamped = i32(raw, OFF_META_PAGE_SIZE)
    elif ptype == PAGE_HEAP_META:
        stamped = i32(raw, OFF_HEAP_META_PAGE_SIZE)
    else:
        raise PageError(f"{path}: page 0 type {ptype} is not meta")
    if stamped < HEADER_SIZE + 4 or stamped > 0xFFFF:
        raise PageError(f"{path}: invalid stamped pageSize {stamped}")
    if len(raw) % stamped != 0:
        raise PageError(
            f"{path}: stamped pageSize {stamped} does not divide file length {len(raw)}"
        )
    return stamped


def header_fields(page: bytes) -> dict[str, int]:
    magic = u16(page, OFF_MAGIC)
    if magic != MAGIC:
        raise PageError(f"bad magic 0x{magic:x} (expected 0x{MAGIC:x})")
    return {
        "magic": magic,
        "pageType": page[OFF_PAGE_TYPE],
        "pageId": i32(page, OFF_PAGE_ID),
        "slotCount": u16(page, OFF_SLOT_COUNT),
        "lower": u16(page, OFF_LOWER),
        "upper": u16(page, OFF_UPPER),
    }


def slot_dir_offset(slot_id: int) -> int:
    return HEADER_SIZE + slot_id * SLOT_SIZE


def slot_offset(page: bytes, slot_id: int) -> int:
    return u16(page, slot_dir_offset(slot_id))


def slot_length(page: bytes, slot_id: int) -> int:
    return u16(page, slot_dir_offset(slot_id) + 2)


def free_space(hdr: dict[str, int]) -> int:
    return hdr["upper"] - hdr["lower"]


def get_typed(buf: memoryview, pos: int, col_type: str) -> tuple[Any, int]:
    if col_type == "INT":
        if len(buf) - pos < 4:
            raise PageError("truncated INT")
        return i32(buf, pos), pos + 4
    if col_type == "BOOLEAN":
        if len(buf) - pos < 1:
            raise PageError("truncated BOOLEAN")
        b = buf[pos]
        if b not in (0, 1):
            raise PageError(f"BOOLEAN must be 0 or 1, got {b}")
        return bool(b), pos + 1
    if col_type == "VARCHAR":
        if len(buf) - pos < 2:
            raise PageError("truncated VARCHAR length")
        n = u16(buf, pos)
        pos += 2
        if len(buf) - pos < n:
            raise PageError("truncated VARCHAR bytes")
        return bytes(buf[pos : pos + n]).decode("utf-8"), pos + n
    raise PageError(f"unknown column type {col_type}")


def decode_typed_payload(payload: bytes, types: list[str], *, skip_row_id: bool) -> dict[str, Any]:
    """Decode null-bitmap + typed columns; optionally leading rowId (heap rows)."""
    pos = 0
    row_id = None
    if skip_row_id:
        if len(payload) < 8:
            raise PageError("row truncated before rowId")
        row_id = i64(payload, 0)
        pos = 8
    nb = null_bitmap_bytes(len(types))
    if len(payload) - pos < nb:
        raise PageError("truncated before null bitmap")
    bitmap = payload[pos : pos + nb]
    pos += nb
    values: list[Any] = []
    for i, col_type in enumerate(types):
        is_null = (bitmap[i // 8] & (1 << (i % 8))) != 0
        if is_null:
            values.append(None)
        else:
            val, pos = get_typed(memoryview(payload), pos, col_type)
            values.append(val)
    if pos != len(payload):
        raise PageError(f"trailing bytes: {len(payload) - pos}")
    out: dict[str, Any] = {"values": values}
    if skip_row_id:
        out["rowId"] = row_id
    return out


def decode_key(payload: bytes, types: list[str], key_padding: int = 0) -> list[Any]:
    """Decode index key; ignore trailing teaching pad (INDEX_KEY_PADDING_BYTES)."""
    pos = 0
    nb = null_bitmap_bytes(len(types))
    if len(payload) - pos < nb:
        raise PageError("truncated before null bitmap")
    bitmap = payload[pos : pos + nb]
    pos += nb
    values: list[Any] = []
    for i, col_type in enumerate(types):
        is_null = (bitmap[i // 8] & (1 << (i % 8))) != 0
        if is_null:
            values.append(None)
        else:
            val, pos = get_typed(memoryview(payload), pos, col_type)
            values.append(val)
    remaining = len(payload) - pos
    if remaining == key_padding:
        return values
    if remaining == 0 and key_padding == 0:
        return values
    # Older images / unknown pad: accept trailing zeros as pad.
    if remaining > 0 and all(b == 0 for b in payload[pos:]):
        return values
    raise PageError(f"index key trailing bytes: {remaining} (expected pad {key_padding})")


def decode_row(payload: bytes, types: list[str]) -> dict[str, Any]:
    """Match RowCodec.decodeWithNullPad: try full width, then shorter prefixes."""
    last_err: Exception | None = None
    for cols in range(len(types), 0, -1):
        try:
            decoded = decode_typed_payload(payload, types[:cols], skip_row_id=True)
            values = list(decoded["values"])
            while len(values) < len(types):
                values.append(None)
            return {"rowId": decoded["rowId"], "values": values}
        except PageError as exc:
            last_err = exc
    raise PageError(f"cannot decode row: {last_err}")


def format_values(values: list[Any]) -> str:
    parts = []
    for v in values:
        if v is None:
            parts.append("NULL")
        elif isinstance(v, bool):
            parts.append("true" if v else "false")
        else:
            parts.append(str(v))
    return ", ".join(parts)


def load_catalog(path: Path, index_hint: str | None) -> dict[str, Any]:
    catalog = json.loads(path.read_text(encoding="utf-8"))
    columns = catalog.get("columns") or []
    by_id = {c["columnId"]: c for c in columns}
    indexes = catalog.get("indexes") or []
    index = None
    if index_hint:
        for ix in indexes:
            if ix.get("name") == index_hint:
                index = ix
                break
        # Filename stem often matches index name (idx_users_name / pk_users_id).
        if index is None:
            for ix in indexes:
                if ix.get("name") == Path(index_hint).stem:
                    index = ix
                    break
        if index is None and len(indexes) == 1:
            index = indexes[0]
    elif len(indexes) == 1:
        index = indexes[0]
    if index is None and indexes:
        index = indexes[0]
    if index is None:
        raise PageError(f"{path}: no indexes in catalog")
    key_cols = []
    key_types = []
    for cid in index["columnIds"]:
        col = by_id[cid]
        key_cols.append(col["name"])
        key_types.append(col["type"])
    heap_types = [c["type"] for c in columns]
    heap_names = [c["name"] for c in columns]
    return {
        "table": catalog.get("name"),
        "indexName": index["name"],
        "keyColumns": key_cols,
        "keyTypes": key_types,
        "heapColumns": heap_names,
        "heapTypes": heap_types,
        "unique": bool(index.get("unique")),
        "allIndexNames": [ix.get("name") for ix in indexes],
    }


def parse_index_file(pages: list[bytes], key_types: list[str]) -> dict[str, Any]:
    nodes: list[dict[str, Any]] = []
    edges: list[dict[str, Any]] = []
    leaves: dict[str, Any] = {}
    meta_info: dict[str, Any] = {}
    key_padding = 0

    for page_num, page in enumerate(pages):
        hdr = header_fields(page)
        if hdr["pageId"] != page_num:
            # Still usable, but flag mismatch.
            pass
        ptype = hdr["pageType"]
        label_type = TYPE_NAMES.get(ptype, f"TYPE{ptype}")
        node_id = f"idx:{page_num}"
        free = free_space(hdr)

        if ptype == PAGE_INDEX_META:
            root = i32(page, OFF_META_ROOT)
            height = i32(page, OFF_META_HEIGHT)
            stamped = i32(page, OFF_META_PAGE_SIZE)
            key_padding = i32(page, OFF_META_KEY_PADDING)
            if key_padding < 0:
                key_padding = 0
            meta_info = {
                "rootPageId": root,
                "height": height,
                "pageSize": stamped,
                "keyPaddingBytes": key_padding,
            }
            nodes.append(
                {
                    "id": node_id,
                    "pageId": page_num,
                    "kind": "META",
                    "label": f"META p{page_num}\nroot={root} h={height}\npageSize={stamped}\npad={key_padding}",
                    "title": f"INDEX_META page {page_num}",
                    "free": free,
                }
            )
            if root >= 0:
                edges.append(
                    {
                        "from": node_id,
                        "to": f"idx:{root}",
                        "label": "root",
                        "dashes": False,
                        "kind": "root",
                    }
                )
            continue

        if ptype == PAGE_INDEX_INTERNAL:
            left = i32(page, OFF_INTERNAL_LEFT)
            live = 0
            nodes.append(
                {
                    "id": node_id,
                    "pageId": page_num,
                    "kind": "INTERNAL",
                    "label": f"INTERNAL p{page_num}\n{hdr['slotCount']} slots · free {free}",
                    "title": f"INDEX_INTERNAL page {page_num}",
                    "free": free,
                    "leftChild": left,
                }
            )
            if left >= 0:
                edges.append(
                    {
                        "from": node_id,
                        "to": f"idx:{left}",
                        "label": "left",
                        "kind": "child",
                    }
                )
            for slot in range(hdr["slotCount"]):
                length = slot_length(page, slot)
                if length == 0:
                    continue
                live += 1
                off = slot_offset(page, slot)
                key_bytes = page[off : off + length - CHILD_BYTES]
                child = i32(page, off + length - CHILD_BYTES)
                try:
                    key_vals = decode_key(key_bytes, key_types, key_padding)
                    key_disp = format_values(key_vals)
                except PageError:
                    key_disp = f"<{len(key_bytes)}B>"
                    key_vals = None
                edges.append(
                    {
                        "from": node_id,
                        "to": f"idx:{child}",
                        "label": f"≥ {key_disp}",
                        "kind": "child",
                        "key": key_vals,
                    }
                )
            nodes[-1]["liveSlots"] = live
            continue

        if ptype == PAGE_INDEX_LEAF:
            next_leaf = i32(page, OFF_LEAF_NEXT)
            entries = []
            for slot in range(hdr["slotCount"]):
                length = slot_length(page, slot)
                if length == 0:
                    continue
                off = slot_offset(page, slot)
                key_bytes = page[off : off + length - RID_BYTES]
                rid_page = i32(page, off + length - RID_BYTES)
                rid_slot = i32(page, off + length - RID_BYTES + 4)
                try:
                    key_vals = decode_key(key_bytes, key_types, key_padding)
                    key_disp = format_values(key_vals)
                except PageError:
                    key_vals = None
                    key_disp = f"<{len(key_bytes)}B hex>"
                entries.append(
                    {
                        "slotId": slot,
                        "key": key_vals,
                        "keyDisplay": key_disp,
                        "ridPage": rid_page,
                        "ridSlot": rid_slot,
                    }
                )
            leaves[str(page_num)] = {"entries": entries, "nextLeaf": next_leaf}
            nodes.append(
                {
                    "id": node_id,
                    "pageId": page_num,
                    "kind": "LEAF",
                    "label": f"LEAF p{page_num}\n{len(entries)} keys · free {free}",
                    "title": f"INDEX_LEAF page {page_num} — click for entries",
                    "free": free,
                    "entryCount": len(entries),
                    "nextLeaf": next_leaf,
                }
            )
            if next_leaf >= 0:
                edges.append(
                    {
                        "from": node_id,
                        "to": f"idx:{next_leaf}",
                        "label": "nextLeaf",
                        "dashes": True,
                        "kind": "nextLeaf",
                    }
                )
            continue

        raise PageError(f"unexpected page type {ptype} at idx page {page_num}")

    return {"meta": meta_info, "nodes": nodes, "edges": edges, "leaves": leaves}


def parse_heap_file(pages: list[bytes], heap_types: list[str], heap_names: list[str]) -> dict[str, Any]:
    heap_pages: dict[str, Any] = {}
    heap_meta: dict[str, Any] = {}
    for page_num, page in enumerate(pages):
        hdr = header_fields(page)
        if page_num == 0:
            if hdr["pageType"] != PAGE_HEAP_META:
                raise PageError(f"ibd page 0: expected HEAP_META, got {hdr['pageType']}")
            stamped = i32(page, OFF_HEAP_META_PAGE_SIZE)
            heap_meta = {"pageSize": stamped}
            continue
        if hdr["pageType"] != PAGE_HEAP:
            raise PageError(f"ibd page {page_num}: expected HEAP, got {hdr['pageType']}")
        slots = []
        for slot in range(hdr["slotCount"]):
            length = slot_length(page, slot)
            if length == 0:
                slots.append({"slotId": slot, "live": False})
                continue
            off = slot_offset(page, slot)
            payload = page[off : off + length]
            try:
                decoded = decode_row(payload, heap_types)
                row = {
                    "slotId": slot,
                    "live": True,
                    "rowId": decoded["rowId"],
                    "values": decoded["values"],
                    "columns": {
                        heap_names[i]: decoded["values"][i] for i in range(len(heap_names))
                    },
                }
            except PageError as exc:
                row = {
                    "slotId": slot,
                    "live": True,
                    "error": str(exc),
                    "payloadLen": length,
                }
            slots.append(row)
        heap_pages[str(page_num)] = {
            "pageId": page_num,
            "slotCount": hdr["slotCount"],
            "free": free_space(hdr),
            "slots": slots,
        }
    return {"meta": heap_meta, "pages": heap_pages}


def add_heap_nodes(
    graph: dict[str, Any],
    heap_pages: dict[str, Any],
    referenced: set[int],
) -> None:
    """
    Record referenced heap page ids for the UI, but do not put HEAP nodes or Rid
    edges on the canvas — those many-to-many links clutter the B+ tree view.
    Heap detail is shown in the side panel after a leaf entry click.
    """
    graph["referencedHeapPages"] = sorted(referenced)
    _ = heap_pages


def build_model(
    idx_path: Path,
    ibd_path: Path,
    catalog_path: Path,
    index_name: str | None,
    page_size: int | None = None,
) -> dict[str, Any]:
    hint = index_name or idx_path.stem
    schema = load_catalog(catalog_path, hint)
    if page_size is None:
        page_size = detect_page_size(idx_path)
        heap_stamp = detect_page_size(ibd_path)
        if heap_stamp != page_size:
            raise PageError(
                f"idx stamped pageSize {page_size} != ibd stamped pageSize {heap_stamp}"
            )
    idx_pages = read_pages(idx_path, page_size)
    ibd_pages = read_pages(ibd_path, page_size)
    graph = parse_index_file(idx_pages, schema["keyTypes"])
    heap = parse_heap_file(ibd_pages, schema["heapTypes"], schema["heapColumns"])
    heap_pages = heap["pages"]
    referenced: set[int] = set()
    for leaf in graph["leaves"].values():
        for entry in leaf["entries"]:
            referenced.add(entry["ridPage"])
    add_heap_nodes(graph, heap_pages, referenced)
    return {
        "source": {
            "idx": str(idx_path),
            "ibd": str(ibd_path),
            "catalog": str(catalog_path),
            "pageSize": page_size,
        },
        "schema": schema,
        "meta": graph["meta"],
        "heapMeta": heap["meta"],
        "nodes": graph["nodes"],
        "edges": graph["edges"],
        "leaves": graph["leaves"],
        "heapPages": heap_pages,
        "referencedHeapPages": graph.get("referencedHeapPages", []),
        "note": "Disk image only — CHECKPOINT / stop the server for a consistent dump.",
    }


def discover_table_dir(table_dir: Path) -> tuple[Path, Path, list[Path]]:
    """Resolve catalog.json, one .ibd, and every .idx under a table directory."""
    table_dir = table_dir.resolve()
    if not table_dir.is_dir():
        raise PageError(f"table dir not found: {table_dir}")
    catalog = table_dir / "catalog.json"
    if not catalog.is_file():
        raise PageError(f"missing catalog.json in {table_dir}")
    ibds = sorted(table_dir.glob("*.ibd"))
    if not ibds:
        raise PageError(f"no .ibd in {table_dir}")
    idxs = sorted(table_dir.glob("*.idx"))
    if not idxs:
        raise PageError(f"no .idx files in {table_dir}")
    return catalog, ibds[0], idxs


def build_table_bundle(
    table_dir: Path,
    page_size: int | None = None,
) -> dict[str, Any]:
    """
    Parse every .idx in a table directory into one HTML model with shared heap pages
    and one tab per index.
    """
    catalog_path, ibd_path, idx_paths = discover_table_dir(table_dir)
    # Resolve page size from the first index (all stamps must match).
    if page_size is None:
        page_size = detect_page_size(idx_paths[0])
        heap_stamp = detect_page_size(ibd_path)
        if heap_stamp != page_size:
            raise PageError(
                f"idx stamped pageSize {page_size} != ibd stamped pageSize {heap_stamp}"
            )

    # Heap is shared — decode once from catalog column layout.
    base_schema = load_catalog(catalog_path, idx_paths[0].stem)
    ibd_pages = read_pages(ibd_path, page_size)
    heap = parse_heap_file(ibd_pages, base_schema["heapTypes"], base_schema["heapColumns"])
    heap_pages = heap["pages"]

    indexes: list[dict[str, Any]] = []
    for idx_path in idx_paths:
        schema = load_catalog(catalog_path, idx_path.stem)
        stamped = detect_page_size(idx_path)
        if stamped != page_size:
            raise PageError(
                f"{idx_path.name}: stamped pageSize {stamped} != {page_size}"
            )
        graph = parse_index_file(read_pages(idx_path, page_size), schema["keyTypes"])
        referenced: set[int] = set()
        for leaf in graph["leaves"].values():
            for entry in leaf["entries"]:
                referenced.add(entry["ridPage"])
        cols = ", ".join(schema["keyColumns"])
        uniq = " UNIQUE" if schema.get("unique") else ""
        indexes.append(
            {
                "id": schema["indexName"],
                "label": f"{schema['indexName']} ({cols}){uniq}",
                "schema": schema,
                "meta": graph["meta"],
                "nodes": graph["nodes"],
                "edges": graph["edges"],
                "leaves": graph["leaves"],
                "referencedHeapPages": sorted(referenced),
                "source": {
                    "idx": str(idx_path),
                    "ibd": str(ibd_path),
                    "catalog": str(catalog_path),
                    "pageSize": page_size,
                },
            }
        )

    return {
        "table": base_schema.get("table") or table_dir.name,
        "tableDir": str(table_dir.resolve()),
        "heapMeta": heap["meta"],
        "heapPages": heap_pages,
        "indexes": indexes,
        "note": "Disk image only — CHECKPOINT / stop the server for a consistent dump.",
    }


def model_to_bundle(model: dict[str, Any]) -> dict[str, Any]:
    """Wrap a single-index build_model() result into the multi-tab bundle shape."""
    schema = model["schema"]
    cols = ", ".join(schema["keyColumns"])
    uniq = " UNIQUE" if schema.get("unique") else ""
    return {
        "table": schema.get("table") or "?",
        "tableDir": str(Path(model["source"]["idx"]).parent),
        "heapMeta": model.get("heapMeta") or {},
        "heapPages": model["heapPages"],
        "indexes": [
            {
                "id": schema["indexName"],
                "label": f"{schema['indexName']} ({cols}){uniq}",
                "schema": schema,
                "meta": model["meta"],
                "nodes": model["nodes"],
                "edges": model["edges"],
                "leaves": model["leaves"],
                "referencedHeapPages": model.get("referencedHeapPages", []),
                "source": model["source"],
            }
        ],
        "note": model.get("note", ""),
    }


HTML_TEMPLATE = r"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Page graph — __TITLE__</title>
  <script src="https://unpkg.com/vis-network@9.1.9/standalone/umd/vis-network.min.js"></script>
  <style>
    :root {
      --bg: #0f172a;
      --panel: #1e293b;
      --border: #334155;
      --text: #e2e8f0;
      --muted: #94a3b8;
      --accent: #38bdf8;
      --leaf: #4ade80;
      --heap: #fbbf24;
      --warn: #f87171;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0;
      font-family: "Segoe UI", system-ui, sans-serif;
      background: var(--bg);
      color: var(--text);
      height: 100vh;
      display: flex;
      flex-direction: column;
    }
    header {
      padding: 0.75rem 1.25rem;
      border-bottom: 1px solid var(--border);
      background: var(--panel);
      display: flex;
      flex-wrap: wrap;
      gap: 0.75rem 1.5rem;
      align-items: center;
      justify-content: space-between;
    }
    .header-left {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem 1.25rem;
      align-items: baseline;
      min-width: 0;
    }
    header h1 {
      margin: 0;
      font-size: 1.1rem;
      font-weight: 600;
    }
    header .meta { color: var(--muted); font-size: 0.85rem; }
    #index-tabs {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      margin-left: auto;
      justify-content: flex-end;
    }
    .index-tab {
      border: 1px solid var(--border);
      background: #0f172a;
      color: var(--muted);
      padding: 0.35rem 0.75rem;
      border-radius: 999px;
      font-size: 0.8rem;
      cursor: pointer;
    }
    .index-tab:hover { color: var(--text); border-color: var(--accent); }
    .index-tab.active {
      background: #0c4a6e;
      color: var(--accent);
      border-color: var(--accent);
    }
    main {
      flex: 1;
      display: grid;
      grid-template-columns: 1.4fr 1fr;
      min-height: 0;
    }
    #network {
      border-right: 1px solid var(--border);
      min-height: 60vh;
      height: 100%;
    }
    #detail {
      overflow: auto;
      padding: 1rem 1.25rem;
      background: #111827;
    }
    #detail h2 {
      margin: 0 0 0.5rem;
      font-size: 1rem;
    }
    #detail h3 {
      margin: 1.25rem 0 0.5rem;
      font-size: 0.9rem;
      color: var(--accent);
    }
    .hint { color: var(--muted); font-size: 0.85rem; margin-bottom: 1rem; }
    .crumb {
      font-family: ui-monospace, Consolas, monospace;
      font-size: 0.8rem;
      color: var(--leaf);
      margin-bottom: 0.75rem;
    }
    .row-card {
      border: 1px solid var(--border);
      background: var(--panel);
      border-radius: 8px;
      padding: 0.75rem 1rem;
      margin: 0.75rem 0 1rem;
    }
    .row-card h3 {
      margin: 0 0 0.6rem;
      font-size: 0.9rem;
      color: var(--heap);
    }
    .row-kv {
      display: grid;
      grid-template-columns: minmax(6rem, 30%) 1fr;
      gap: 0.25rem 0.75rem;
      font-size: 0.85rem;
      margin-bottom: 0.35rem;
    }
    .row-kv .k { color: var(--muted); }
    .row-kv .v code { color: var(--text); }
    .heap-chips {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
      margin: 0.5rem 0 0.75rem;
    }
    .chip {
      display: inline-block;
      padding: 0.2rem 0.55rem;
      border-radius: 999px;
      font-size: 0.75rem;
      background: #78350f;
      color: var(--heap);
      border: 1px solid #a16207;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      font-size: 0.85rem;
    }
    th, td {
      border: 1px solid var(--border);
      padding: 0.35rem 0.5rem;
      text-align: left;
    }
    th { background: var(--panel); color: var(--muted); font-weight: 500; }
    tr.entry-row { cursor: pointer; }
    tr.entry-row:hover { background: #1e293b; }
    tr.entry-row.active { background: #0c4a6e; outline: 1px solid var(--accent); }
    tr.slot-row.highlight {
      background: #422006;
      outline: 1px solid var(--heap);
    }
    tr.tombstone { color: var(--muted); font-style: italic; }
    .pill {
      display: inline-block;
      padding: 0.1rem 0.45rem;
      border-radius: 999px;
      font-size: 0.75rem;
      background: #334155;
      color: var(--muted);
    }
    .pill.leaf { background: #14532d; color: var(--leaf); }
    .pill.heap { background: #78350f; color: var(--heap); }
    code { font-family: ui-monospace, Consolas, monospace; font-size: 0.8rem; }
    @media (max-width: 900px) {
      main { grid-template-columns: 1fr; grid-template-rows: 45vh 1fr; }
      #network { border-right: none; border-bottom: 1px solid var(--border); }
      #index-tabs { margin-left: 0; width: 100%; justify-content: flex-start; }
    }
  </style>
</head>
<body>
  <header>
    <div class="header-left">
      <h1>Index → heap page graph</h1>
      <span class="meta" id="header-meta"></span>
    </div>
    <div id="index-tabs" aria-label="Indexes"></div>
  </header>
  <main>
    <div id="network"></div>
    <aside id="detail">
      <p class="hint">Click a <span class="pill leaf">LEAF</span> node, then an entry row to open the
        <span class="pill heap">HEAP</span> row and page (Rid links are not drawn on the tree).</p>
      <div id="panel">
        <h2>Select a leaf</h2>
        <p class="hint">Canvas shows the B+ tree: internal root → leaf children (solid), sibling scan chain (dashed nextLeaf). Heap rows appear here after you pick an entry.</p>
      </div>
    </aside>
  </main>
  <script>
    // Bundle: { table, heapPages, heapMeta, indexes: [...], note }
    const DATA = __DATA__;

    const headerMeta = document.getElementById("header-meta");
    const tabsEl = document.getElementById("index-tabs");
    const panel = document.getElementById("panel");
    const networkEl = document.getElementById("network");

    let activeIndex = null;
    let network = null;
    let nodes = null;
    let edges = null;
    let nextEdgeId = 0;
    let overlayEdgeIds = [];
    let selectedLeaf = null;
    let selectedEntry = null;

    // vis-network defaults hover to a light fill; without an explicit hover color,
    // light text (#e2e8f0) becomes unreadable. Keep hover/highlight on dark fills.
    const colorFor = (kind) => {
      const dark = (bg, border, hiBg, hiBorder) => ({
        background: bg,
        border,
        highlight: { background: hiBg, border: hiBorder },
        hover: { background: hiBg, border: hiBorder },
      });
      switch (kind) {
        case "META": return dark("#312e81", "#818cf8", "#4338ca", "#a5b4fc");
        case "INTERNAL": return dark("#1e3a5f", "#38bdf8", "#075985", "#7dd3fc");
        case "LEAF": return dark("#14532d", "#4ade80", "#166534", "#86efac");
        case "HEAP": return dark("#78350f", "#fbbf24", "#92400e", "#fcd34d");
        default: return dark("#334155", "#94a3b8", "#475569", "#cbd5e1");
      }
    };

    /**
     * vis-network hierarchical layout treats every directed edge as a level constraint.
     * nextLeaf links are sibling scan order, not tree depth — without explicit levels
     * the layout stacks all leaves in one vertical chain under the first child.
     */
    function computeTreeLayout(meta, nodesArr, edgesArr, leaves) {
      const levels = {};
      const hasMeta = nodesArr.some(n => n.kind === "META");
      const childEdges = edgesArr.filter(e => e.kind === "child" || e.kind === "root");
      nodesArr.filter(n => n.kind === "META").forEach(n => { levels[n.id] = 0; });

      const rootId = meta.rootPageId >= 0 ? `idx:${meta.rootPageId}` : null;
      if (rootId) {
        levels[rootId] = hasMeta ? 1 : 0;
        let frontier = [rootId];
        while (frontier.length) {
          const next = [];
          for (const from of frontier) {
            for (const e of childEdges) {
              if (e.from === from && levels[e.to] === undefined) {
                levels[e.to] = levels[from] + 1;
                next.push(e.to);
              }
            }
          }
          frontier = next;
        }
      }

      const leafNodes = nodesArr.filter(n => n.kind === "LEAF");
      const nextTargets = new Set();
      for (const info of Object.values(leaves)) {
        if (info.nextLeaf >= 0) {
          nextTargets.add(info.nextLeaf);
        }
      }
      let head = leafNodes.find(n => !nextTargets.has(n.pageId));
      if (!head && leafNodes.length) {
        head = leafNodes.reduce((a, b) => (a.pageId < b.pageId ? a : b));
      }
      const leafOrder = {};
      let order = 0;
      const visited = new Set();
      let cur = head;
      while (cur && !visited.has(cur.pageId)) {
        visited.add(cur.pageId);
        leafOrder[cur.id] = order++;
        const nl = leaves[String(cur.pageId)]?.nextLeaf ?? -1;
        cur = nl >= 0 ? leafNodes.find(n => n.pageId === nl) : null;
      }
      for (const n of leafNodes) {
        if (leafOrder[n.id] === undefined) {
          leafOrder[n.id] = order++;
        }
      }
      return { levels, leafOrder };
    }

    function esc(s) {
      return String(s)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;");
    }

    function fmtVal(v) {
      if (v === null || v === undefined) return "<em>NULL</em>";
      if (typeof v === "boolean") return v ? "true" : "false";
      return esc(v);
    }

    function clearRidOverlay() {
      if (!edges || !nodes) return;
      if (overlayEdgeIds.length) {
        edges.remove(overlayEdgeIds);
        overlayEdgeIds = [];
      }
      const heapIds = nodes.getIds().filter(id => String(id).startsWith("ibd:"));
      if (heapIds.length) {
        nodes.remove(heapIds);
      }
    }

    function ensureHeapNode(pageId) {
      const id = `ibd:${pageId}`;
      if (nodes.get(id)) {
        return id;
      }
      const info = DATA.heapPages[String(pageId)];
      const live = info ? info.slots.filter(s => s.live).length : 0;
      const free = info ? info.free : -1;
      nodes.add({
        id,
        label: `HEAP p${pageId}\n${live} live · free ${free}`,
        title: `HEAP page ${pageId}`,
        shape: "box",
        color: colorFor("HEAP"),
        font: { color: "#e2e8f0", multi: true },
        margin: 10,
        kind: "HEAP",
        pageId,
        level: 4,
      });
      return id;
    }

    function showEntryRid(leafPageId, entry) {
      clearRidOverlay();
      const heapId = ensureHeapNode(entry.ridPage);
      const eid = `rid-overlay-${nextEdgeId++}`;
      edges.add({
        id: eid,
        from: `idx:${leafPageId}`,
        to: heapId,
        label: `Rid → (${entry.ridPage},${entry.ridSlot})`,
        dashes: true,
        arrows: "to",
        color: { color: "#fbbf24" },
        font: { color: "#fcd34d", size: 11, strokeWidth: 0 },
        kind: "rid",
      });
      overlayEdgeIds.push(eid);
      network.selectNodes([`idx:${leafPageId}`, heapId]);
      network.focus(heapId, { scale: 1.05, animation: true });
    }

    function heapLiveCount(page) {
      return page.slots.filter(s => s.live).length;
    }

    function renderHeapTable(pageId, highlightSlot) {
      const page = DATA.heapPages[String(pageId)];
      if (!page) {
        return `<p class="hint" style="color:var(--warn)">Heap page ${pageId} not in .ibd file.</p>`;
      }
      const cols = activeIndex.schema.heapColumns;
      const head = ["slot", "rowId", ...cols].map(c => `<th>${esc(c)}</th>`).join("");
      const body = page.slots.map(s => {
        if (!s.live) {
          return `<tr class="tombstone"><td>${s.slotId}</td><td colspan="${1 + cols.length}">tombstone</td></tr>`;
        }
        if (s.error) {
          return `<tr><td>${s.slotId}</td><td colspan="${1 + cols.length}">decode error: ${esc(s.error)}</td></tr>`;
        }
        const hl = s.slotId === highlightSlot ? " highlight" : "";
        const vals = cols.map(c => `<td>${fmtVal(s.columns[c])}</td>`).join("");
        return `<tr class="slot-row${hl}"><td>${s.slotId}</td><td>${s.rowId}</td>${vals}</tr>`;
      }).join("");
      return `
        <table>
          <thead><tr>${head}</tr></thead>
          <tbody>${body}</tbody>
        </table>`;
    }

    function renderRowCard(leafPageId, entry) {
      const page = DATA.heapPages[String(entry.ridPage)];
      const schema = activeIndex.schema;
      const cols = schema.heapColumns || [];
      let rowId = "?";
      let colRows = "";
      if (page) {
        const slot = page.slots.find(s => s.slotId === entry.ridSlot);
        if (slot && slot.live && !slot.error) {
          rowId = slot.rowId;
          colRows = cols.map(c => `
            <div class="row-kv"><span class="k">${esc(c)}</span>
              <span class="v">${fmtVal(slot.columns[c])}</span></div>`).join("");
        } else if (slot && slot.error) {
          colRows = `<p class="hint" style="color:var(--warn)">decode error: ${esc(slot.error)}</p>`;
        } else if (slot && !slot.live) {
          colRows = `<p class="hint">slot is a tombstone</p>`;
        } else {
          colRows = `<p class="hint" style="color:var(--warn)">slot not found on heap page</p>`;
        }
      } else {
        colRows = `<p class="hint" style="color:var(--warn)">heap page missing from dump</p>`;
      }

      const chips = page ? `
        <div class="heap-chips">
          <span class="chip">page ${page.pageId}</span>
          <span class="chip">free ${page.free} bytes</span>
          <span class="chip">${page.slotCount} slots</span>
          <span class="chip">${heapLiveCount(page)} live</span>
        </div>` : "";

      return `
        <div class="row-card">
          <h3>Selected row</h3>
          <div class="row-kv"><span class="k">index key</span>
            <span class="v"><code>${esc(entry.keyDisplay)}</code></span></div>
          <div class="row-kv"><span class="k">Rid</span>
            <span class="v"><code>(${entry.ridPage}, ${entry.ridSlot})</code></span></div>
          <div class="row-kv"><span class="k">rowId</span>
            <span class="v"><code>${esc(rowId)}</code></span></div>
          ${colRows}
          ${chips}
          <h3 style="margin-top:0.75rem;color:var(--accent)">Heap page ${entry.ridPage}</h3>
          ${renderHeapTable(entry.ridPage, entry.ridSlot)}
        </div>`;
    }

    function renderLeaf(pageId) {
      clearRidOverlay();
      selectedLeaf = pageId;
      selectedEntry = null;
      const leaf = activeIndex.leaves[String(pageId)];
      if (!leaf) {
        panel.innerHTML = `<h2>Leaf p${pageId}</h2><p class="hint">No entry data.</p>`;
        return;
      }
      const heapTargets = [...new Set(leaf.entries.map(e => e.ridPage))].sort((a, b) => a - b);
      const rows = leaf.entries.map((e, i) => `
        <tr class="entry-row" data-idx="${i}">
          <td>${e.slotId}</td>
          <td><code>${esc(e.keyDisplay)}</code></td>
          <td><code>(${e.ridPage}, ${e.ridSlot})</code></td>
        </tr>`).join("");
      panel.innerHTML = `
        <div class="crumb">idx leaf p${pageId} · ${leaf.entries.length} live · nextLeaf=${leaf.nextLeaf}</div>
        <p class="hint">Keys in this leaf point at heap pages: ${heapTargets.map(p => "p" + p).join(", ") || "(none)"}</p>
        <h2>Leaf entries</h2>
        <p class="hint">Click a row to show the heap row card (key, Rid, rowId, columns) and the full heap page.</p>
        <table>
          <thead><tr><th>slot</th><th>key</th><th>Rid</th></tr></thead>
          <tbody id="entry-body">${rows || '<tr><td colspan="3">empty leaf</td></tr>'}</tbody>
        </table>
        <div id="heap-block"></div>`;
      document.querySelectorAll(".entry-row").forEach(tr => {
        tr.addEventListener("click", () => {
          const i = Number(tr.dataset.idx);
          selectEntry(pageId, i);
        });
      });
    }

    function selectEntry(leafPageId, entryIndex) {
      selectedEntry = entryIndex;
      document.querySelectorAll(".entry-row").forEach((tr, i) => {
        tr.classList.toggle("active", i === entryIndex);
      });
      const entry = activeIndex.leaves[String(leafPageId)].entries[entryIndex];
      showEntryRid(leafPageId, entry);
      const block = document.getElementById("heap-block");
      if (block) {
        block.innerHTML = `
          <div class="crumb">idx leaf p${leafPageId} / key ${esc(entry.keyDisplay)} → Rid(${entry.ridPage}, ${entry.ridSlot})</div>
          ${renderRowCard(leafPageId, entry)}`;
      }
    }

    function updateHeader() {
      const schema = activeIndex.schema;
      const pageSize =
        activeIndex.source?.pageSize ??
        activeIndex.meta?.pageSize ??
        DATA.heapMeta?.pageSize ??
        "?";
      headerMeta.textContent =
        `${DATA.table || schema.table || "?"} · ${schema.indexName} (${schema.keyColumns.join(", ")})` +
        ` · height=${activeIndex.meta.height ?? "?"} root=${activeIndex.meta.rootPageId ?? "?"}` +
        ` · pageSize=${pageSize}` +
        (DATA.indexes.length > 1 ? ` · ${DATA.indexes.length} indexes` : "");
    }

    function mountIndex(index) {
      activeIndex = index;
      selectedLeaf = null;
      selectedEntry = null;
      clearRidOverlay();
      updateHeader();

      // Index tree only — HEAP / Rid edges stay out of the canvas to avoid spaghetti.
      const treeNodes = index.nodes.filter(n => n.kind !== "HEAP");
      const treeEdges = index.edges.filter(e => e.kind !== "rid");
      const { levels, leafOrder } = computeTreeLayout(
        index.meta ?? {},
        treeNodes,
        treeEdges,
        index.leaves ?? {},
      );

      nodes = new vis.DataSet(treeNodes.map(n => ({
        id: n.id,
        label: n.label,
        title: n.title,
        shape: n.kind === "LEAF" ? "ellipse" : "box",
        color: colorFor(n.kind),
        font: { color: "#e2e8f0", multi: true },
        margin: 10,
        kind: n.kind,
        pageId: n.pageId,
        level: levels[n.id],
        ...(n.kind === "LEAF" && leafOrder[n.id] !== undefined ? { order: leafOrder[n.id] } : {}),
      })));

      edges = new vis.DataSet(treeEdges.map((e, i) => ({
        id: i,
        from: e.from,
        to: e.to,
        label: e.label || "",
        dashes: !!e.dashes,
        arrows: "to",
        color: e.kind === "nextLeaf"
          ? { color: "#4ade80" }
          : (e.color || { color: "#64748b" }),
        font: { color: "#94a3b8", size: 11, strokeWidth: 0 },
        kind: e.kind,
        smooth: e.kind === "nextLeaf"
          ? { type: "curvedCW", roundness: 0.15 }
          : { type: "cubicBezier", forceDirection: "vertical", roundness: 0.4 },
      })));

      nextEdgeId = treeEdges.length;
      overlayEdgeIds = [];

      if (network) {
        network.destroy();
        network = null;
      }
      network = new vis.Network(
        networkEl,
        { nodes, edges },
        {
          layout: {
            hierarchical: {
              enabled: true,
              direction: "UD",
              // Child edges set explicit level/order; nextLeaf stays on the leaf row.
              sortMethod: "directed",
              levelSeparation: 120,
              nodeSpacing: 180,
              treeSpacing: 220,
              blockShifting: true,
              edgeMinimization: false,
              parentCentralization: true,
            },
          },
          physics: false,
          interaction: { hover: true, tooltipDelay: 120 },
          nodes: {
            font: { color: "#e2e8f0", multi: true },
            // Chosen labels stay light on our dark hover fills (not black-on-default).
            chosen: { label: false, node: true },
          },
        }
      );
      network.fit({ animation: { duration: 300, easingFunction: "easeInOutQuad" } });

      network.on("click", (params) => {
        if (!params.nodes.length) {
          clearRidOverlay();
          return;
        }
        const id = params.nodes[0];
        const node = nodes.get(id);
        if (!node) return;
        if (node.kind === "LEAF") {
          renderLeaf(node.pageId);
        } else if (node.kind === "HEAP") {
          panel.innerHTML = `
            <div class="crumb">ibd page ${node.pageId}</div>
            <h2>Heap page ${node.pageId}</h2>
            <div id="heap-block"></div>`;
          const page = DATA.heapPages[String(node.pageId)];
          const chips = page ? `
            <div class="heap-chips">
              <span class="chip">page ${page.pageId}</span>
              <span class="chip">free ${page.free} bytes</span>
              <span class="chip">${page.slotCount} slots</span>
              <span class="chip">${heapLiveCount(page)} live</span>
            </div>` : "";
          document.getElementById("heap-block").innerHTML =
            chips + renderHeapTable(node.pageId, -1);
        } else {
          clearRidOverlay();
          panel.innerHTML = `
            <h2>${esc(node.kind)} p${node.pageId}</h2>
            <p class="hint">${esc(node.title || "")}</p>
            <p class="hint">Select a LEAF to inspect keys; click an entry to follow one Rid into the heap.</p>`;
        }
      });

      panel.innerHTML = `
        <h2>Select a leaf</h2>
        <p class="hint">Index <code>${esc(index.id)}</code> — canvas shows the B+ tree. Click a LEAF, then an entry for the row card.</p>`;
    }

    function switchIndex(indexId) {
      const index = DATA.indexes.find(ix => ix.id === indexId);
      if (!index) return;
      tabsEl.querySelectorAll(".index-tab").forEach(btn => {
        btn.classList.toggle("active", btn.dataset.id === indexId);
      });
      mountIndex(index);
    }

    function buildTabs() {
      tabsEl.innerHTML = "";
      for (const ix of DATA.indexes) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "index-tab";
        btn.dataset.id = ix.id;
        btn.textContent = ix.label || ix.id;
        btn.addEventListener("click", () => switchIndex(ix.id));
        tabsEl.appendChild(btn);
      }
    }

    if (!DATA.indexes || !DATA.indexes.length) {
      headerMeta.textContent = `${DATA.table || "?"} · no indexes`;
      panel.innerHTML = `<h2>No indexes</h2><p class="hint">Bundle has no .idx entries.</p>`;
    } else {
      buildTabs();
      switchIndex(DATA.indexes[0].id);
    }
  </script>
</body>
</html>
"""


def write_html(bundle: dict[str, Any], out_path: Path) -> None:
    """Serialize a table/index bundle into the self-contained HTML viewer."""
    title = bundle.get("table") or "?"
    if len(bundle.get("indexes") or []) == 1:
        schema = bundle["indexes"][0].get("schema") or {}
        title = f"{schema.get('table', title)}.{schema.get('indexName', 'index')}"
    elif bundle.get("indexes"):
        title = f"{title} ({len(bundle['indexes'])} indexes)"
    payload = json.dumps(bundle, ensure_ascii=False)
    html = HTML_TEMPLATE.replace("__TITLE__", title).replace("__DATA__", payload)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(html, encoding="utf-8")


def infer_paths(idx: Path) -> tuple[Path | None, Path | None]:
    """Guess sibling catalog.json and table.ibd next to the .idx file."""
    parent = idx.parent
    catalog = parent / "catalog.json"
    ibd = None
    for candidate in parent.glob("*.ibd"):
        ibd = candidate
        break
    return (catalog if catalog.is_file() else None, ibd)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Build an interactive HTML graph of .idx B+ tree(s) linked to .ibd heap rows. "
            "Pass either --idx (one index) or --table-dir (all .idx in a table folder)."
        )
    )
    src = parser.add_mutually_exclusive_group(required=True)
    src.add_argument("--idx", type=Path, help="Path to a single .idx file")
    src.add_argument(
        "--table-dir",
        type=Path,
        help="Table directory with catalog.json, *.ibd, and one or more *.idx",
    )
    parser.add_argument("--ibd", type=Path, help="Path to table .ibd (with --idx; default: sibling *.ibd)")
    parser.add_argument(
        "--catalog",
        type=Path,
        help="Path to catalog.json (with --idx; default: sibling catalog.json)",
    )
    parser.add_argument(
        "--index-name",
        help="Index name in catalog (with --idx; default: .idx filename stem)",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        help="Page size in bytes (default: stamped PAGE_SIZE from meta page 0)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        help="Output HTML path (default: out/page-graph/<table|index>.html)",
    )
    args = parser.parse_args(argv)

    repo_root = Path(__file__).resolve().parents[2]

    try:
        if args.table_dir is not None:
            table_dir = args.table_dir.resolve()
            bundle = build_table_bundle(table_dir, args.page_size)
            out = args.out
            if out is None:
                out = repo_root / "out" / "page-graph" / f"{bundle['table']}.html"
            else:
                out = out.resolve()
        else:
            idx = args.idx.resolve()
            if not idx.is_file():
                print(f"error: idx not found: {idx}", file=sys.stderr)
                return 1
            guess_catalog, guess_ibd = infer_paths(idx)
            catalog = args.catalog or guess_catalog
            ibd = args.ibd or guess_ibd
            if catalog is None or not catalog.is_file():
                print("error: --catalog required (no sibling catalog.json)", file=sys.stderr)
                return 1
            if ibd is None or not ibd.is_file():
                print("error: --ibd required (no sibling *.ibd)", file=sys.stderr)
                return 1
            catalog = catalog.resolve()
            ibd = ibd.resolve()
            model = build_model(idx, ibd, catalog, args.index_name, args.page_size)
            bundle = model_to_bundle(model)
            out = args.out
            if out is None:
                out = repo_root / "out" / "page-graph" / f"{idx.stem}.html"
            else:
                out = out.resolve()
    except PageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    write_html(bundle, out)
    print(f"wrote {out}")
    print(
        f"  table={bundle.get('table')} indexes={len(bundle.get('indexes') or [])} "
        f"heap_pages={len(bundle.get('heapPages') or {})}"
    )
    for ix in bundle.get("indexes") or []:
        leaf_count = sum(1 for n in ix["nodes"] if n["kind"] == "LEAF")
        entry_count = sum(len(v["entries"]) for v in ix["leaves"].values())
        print(
            f"  - {ix['id']}: height={ix['meta'].get('height')} "
            f"leaves={leaf_count} entries={entry_count} "
            f"pageSize={ix['source'].get('pageSize')}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
