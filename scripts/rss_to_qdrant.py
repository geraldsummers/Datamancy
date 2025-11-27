#!/usr/bin/env python3
"""
Lightweight RSS -> embeddings -> Qdrant ingestor.

Environment variables:
  RSS_FEEDS: comma-separated list of feed URLs
  POLL_INTERVAL_SECONDS: seconds between polls (default 900)
  LOCALAI_URL: base URL for OpenAI-compatible embeddings (default http://localai:8080/v1)
  EMBED_MODEL: embeddings model name (default embed-small)
  QDRANT_URL: Qdrant REST URL (default http://localhost:6333)
  QDRANT_API_KEY: optional API key for Qdrant

Writes to collection: rss_aggregation (created by bootstrap)
"""
import hashlib
import json
import os
import time
from typing import Dict, Any, List

import requests
import feedparser


def dget(d: Dict[str, Any], key: str, default: str = "") -> str:
    v = d.get(key)
    if isinstance(v, (list, tuple)):
        return v[0] if v else default
    return str(v or default)


def embed(texts: List[str], base_url: str, model: str) -> List[List[float]]:
    url = base_url.rstrip("/") + "/embeddings"
    payload = {"input": texts, "model": model}
    r = requests.post(url, json=payload, timeout=30)
    r.raise_for_status()
    data = r.json().get("data", [])
    return [item["embedding"] for item in data]


def upsert_qdrant(points: List[Dict[str, Any]], base_url: str, api_key: str = "") -> None:
    url = base_url.rstrip("/") + "/collections/rss_aggregation/points?wait=true"
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["api-key"] = api_key
    payload = {"points": points}
    r = requests.put(url, headers=headers, data=json.dumps(payload), timeout=30)
    r.raise_for_status()


def normalize_entry(feed_url: str, e: Dict[str, Any]) -> Dict[str, Any]:
    title = dget(e, "title")
    summary = dget(e, "summary", dget(e, "description", ""))
    link = dget(e, "link")
    published = dget(e, "published", dget(e, "updated", ""))
    content_text = (title + "\n\n" + summary).strip()
    uid_src = link or (title + published)
    uid = hashlib.sha1(uid_src.encode("utf-8", errors="ignore")).hexdigest()
    payload = {
        "source": feed_url,
        "title": title,
        "summary": summary,
        "link": link,
        "published": published,
    }
    return {"id": uid, "text": content_text, "payload": payload}


def main() -> int:
    feeds_env = os.environ.get("RSS_FEEDS", "").strip()
    if not feeds_env:
        # Sensible defaults
        feeds = [
            "https://hnrss.org/frontpage",
            "https://planetpython.org/rss20.xml",
            "https://www.reddit.com/r/MachineLearning/.rss",
        ]
    else:
        feeds = [u.strip() for u in feeds_env.split(",") if u.strip()]

    poll = int(os.environ.get("POLL_INTERVAL_SECONDS", "900"))
    localai = os.environ.get("LOCALAI_URL", "http://localai:8080/v1")
    model = os.environ.get("EMBED_MODEL", "embed-small")
    qdrant = os.environ.get("QDRANT_URL", "http://localhost:6333")
    qdrant_key = os.environ.get("QDRANT_API_KEY", "")

    seen: set[str] = set()
    while True:
        try:
            batch_entries: List[Dict[str, Any]] = []
            for url in feeds:
                parsed = feedparser.parse(url)
                for e in parsed.entries:
                    ne = normalize_entry(url, e)
                    if ne["id"] in seen:
                        continue
                    batch_entries.append(ne)

            if batch_entries:
                texts = [x["text"] for x in batch_entries]
                vectors = embed(texts, base_url=localai, model=model)
                points = []
                for ne, vec in zip(batch_entries, vectors):
                    points.append({
                        "id": ne["id"],
                        "vector": vec,
                        "payload": ne["payload"],
                    })
                    seen.add(ne["id"])
                upsert_qdrant(points, base_url=qdrant, api_key=qdrant_key)
                print(f"[rss_to_qdrant] Upserted {len(points)} points to Qdrant rss_aggregation")
            else:
                print("[rss_to_qdrant] No new entries")
        except Exception as ex:
            print(f"[rss_to_qdrant] Error: {ex}")
        time.sleep(poll)


if __name__ == "__main__":
    raise SystemExit(main())
