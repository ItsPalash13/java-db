# Implementation choices

This folder records **decisions we actually plan to build**, not general study notes.

Each file explains:

- what problem the choice solves
- what we chose (and what we did not)
- where it shows up in the pipeline or codebase
- phase / scope (what exists today vs later)

Related folders:

- `docs/lld/` — types, wiring, class diagram (must match code)
- `docs/concurrency/` — what threads exist today and what is still unsafe
- `docs/study-report/` and `docs/temp-dev-notes/` — learning notes, not binding decisions

When you implement something described here, update the matching LLD in the same change.
