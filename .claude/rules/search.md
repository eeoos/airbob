---
paths:
  - "**/src/**/search/**/*"
  - "docker/elasticsearch/**/*"
  - "docker/logstash/**/*"
  - "scripts/reindex-accommodations.sh"
---

# Search indexing

Operations: `docs/operations/accommodation-indexing-operations.md` and
`docs/operations/logstash-reindex.md`.

- `AccommodationSearchRefreshRequestedV1` carries only the accommodation UID.
- `AccommodationSearchSnapshotReader` rebuilds the current document from MySQL in its own read-only
  transaction. `AccommodationIndexingService` writes to Elasticsearch after that transaction ends.
- `AccommodationIndexingService` saves a full document only when the accommodation is currently
  `PUBLISHED`; otherwise it deletes the document.
- The `accommodations` alias must point to exactly one versioned write index. Alias bootstrap gates
  consumer startup; full reindex builds a new version index and atomically swaps the alias.
