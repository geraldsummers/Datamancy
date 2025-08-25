-- Initial schema + ANN index + dedup by GUID
CREATE TABLE IF NOT EXISTS rss_items
(
  title      String,
  link       String,
  published  String,
  guid       String,
  summary    String,
  embedding  Array(Float32)
) ENGINE = ReplacingMergeTree
ORDER BY guid;

-- Approximate‑nearest‑neighbour vector index (L2 distance)
CREATE INDEX IF NOT EXISTS rss_items_vec_idx
  ON rss_items VECTOR HNSW(embedding) TYPE L2;