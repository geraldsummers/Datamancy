Data and RAG (Retrieval Augmented Generation)
=============================================

This document consolidates the ingestion plan and vector stack usage. It explains collections, pipelines, and how to bring up the vector profile.

Collections
-----------

Defined in configs/databases/vectors/collections.yaml with vector_size aligned to the embedding model (default 384):
- wiki_projects
- rss_aggregation
- stack_exchange
- macroeconomics_data
- yahoo_finance
- linux_debian_docs
- torrents_csv

Embedding model: use the LiteLLM OpenAI-compatible embeddings endpoint (/v1/embeddings), typically backed by vLLM. Configure the model via EMBED_MODEL.

Bring up the vector profile
---------------------------

Prereqs (once):
1) Start vector infra and initialize collections
   ./scripts/bootstrap-stack.sh bootstrap-vectors
2) Start Qdrant + ClickHouse + main Benthos adapter
   ./scripts/bootstrap-stack.sh up-benthos

Pipelines (Benthos and workers)
-------------------------------

The main Benthos adapter config is at configs/benthos/benthos.yaml with two HTTP inputs:
- POST /ingest/text → embed via LiteLLM → upsert to Qdrant
- POST /ingest/series → insert into ClickHouse (series_values)

Additional ready-made configs exist under configs/benthos/*.yaml for specific sources:
- rss_to_qdrant.yaml
- yahoo_news_to_qdrant.yaml
- wiki_ndjson_to_qdrant.yaml
- linux_debian_ndjson_to_qdrant.yaml
- stackexchange_ndjson_to_qdrant.yaml
- torrents_csv_to_qdrant.yaml
- fred_meta_to_qdrant.yaml
- fred_values_to_clickhouse.yaml

Example run commands (profile: bootstrap_vector_dbs):

- RSS → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-rss

- Yahoo Finance (news RSS) → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-yahoo-news

- FRED series metadata → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-fred-meta

- FRED observations → ClickHouse
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-fred-values

- torrents-csv → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-torrents

- Wiki NDJSON → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-wiki-ndjson

- Linux/Debian docs NDJSON → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-linuxdebian-ndjson

- Stack Exchange NDJSON → Qdrant
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs up -d benthos-stackexchange-ndjson

Verification snippets
---------------------

- Qdrant scroll (example: rss_aggregation):
  # From inside the qdrant container (no host ports are published)
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs exec qdrant \
    curl -s -X POST http://localhost:6333/collections/rss_aggregation/points/scroll \
      -H 'Content-Type: application/json' -d '{"limit":3, "with_payload": true}' | jq '.result.points | length'

- ClickHouse count (series_values):
  docker compose --env-file .env.bootstrap --profile bootstrap_vector_dbs exec clickhouse \
    wget -qO- 'http://localhost:8123/?query=SELECT%20count()%20FROM%20series_values'

Operational notes
-----------------
- Ensure QDRANT_URL/QDRANT_API_KEY (if enabled) and LITELLM_URL are configured for embedding stages.
- For cross-compose communication, prefer internal service names within the same Docker network; host ports are not published by default in this stack.
- Keep collection vector size aligned with the embedding model dimensionality.

Source-specific guidance (summary)
----------------------------------

1) Wiki projects
- Extract with wikiextractor to NDJSON; chunk → embed → upsert to wiki_projects.

2) RSS aggregation
- Use Benthos config configs/infrastructure/benthos/rss_to_qdrant.yaml; dedupe → embed → upsert.

3) Stack Exchange
- Parse Posts.xml; build contexts; chunk → embed → upsert to stack_exchange.

4) Macroeconomics (IMF/WB/OECD/FRED)
- Pull series metadata and notes; embed → macroeconomics_data; values → ClickHouse.

5) Yahoo Finance
- Profiles + news; respect TOS; embed → yahoo_finance.

6) Linux + Debian docs
- Convert HTML/handbooks to text; chunk → embed → upsert to linux_debian_docs.

7) torrents-csv
- Construct textual description from CSV; embed → torrents_csv (id = infohash).
