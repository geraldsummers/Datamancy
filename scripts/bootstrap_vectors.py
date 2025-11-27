#!/usr/bin/env python3
import json
import os
import sys
from urllib import request, error


def http(method, url, data=None, headers=None):
    req = request.Request(url=url, method=method)
    headers = headers or {}
    for k, v in headers.items():
        req.add_header(k, v)
    if data is not None:
        body = json.dumps(data).encode("utf-8")
        req.add_header("Content-Type", "application/json")
    else:
        body = None
    with request.urlopen(req, data=body, timeout=10) as resp:
        return resp.getcode(), json.loads(resp.read().decode("utf-8"))


def ensure_collection(base_url, name, vector_size, distance, api_key=None):
    headers = {}
    if api_key:
        headers["api-key"] = api_key

    # Check if collection exists
    try:
        code, _ = http("GET", f"{base_url}/collections/{name}", headers=headers)
        if code == 200:
            print(f"[bootstrap_vectors] Collection exists: {name}")
            return
    except error.HTTPError as e:
        if e.code != 404:
            raise

    # Create collection
    payload = {
        "vectors": {
            "size": int(vector_size),
            "distance": distance,
        }
    }
    code, resp = http("PUT", f"{base_url}/collections/{name}", data=payload, headers=headers)
    if code not in (200, 201):
        raise RuntimeError(f"Failed to create collection {name}: {code} {resp}")
    print(f"[bootstrap_vectors] Created collection: {name}")


def main():
    if len(sys.argv) < 2:
        print("Usage: bootstrap_vectors.py /path/to/collections.yaml", file=sys.stderr)
        return 2

    try:
        import yaml  # type: ignore
    except Exception:
        print("[bootstrap_vectors] Installing PyYAML...", flush=True)
        os.system("pip install --no-cache-dir pyyaml >/dev/null 2>&1")
        import yaml  # type: ignore

    yaml_path = sys.argv[1]
    with open(yaml_path, "r", encoding="utf-8") as f:
        cfg = yaml.safe_load(f) or {}

    base_url = os.environ.get("QDRANT_URL", "http://localhost:6333").rstrip("/")
    api_key = os.environ.get("QDRANT_API_KEY") or None
    default_size = int(os.environ.get("VECTOR_SIZE") or cfg.get("vector_size") or 384)
    distance = cfg.get("distance", "Cosine")

    collections = cfg.get("collections", [])
    if not collections:
        print("[bootstrap_vectors] No collections defined; exiting.")
        return 0

    for c in collections:
        name = c.get("name")
        if not name:
            continue
        size = int(c.get("vector_size") or default_size)
        dist = c.get("distance") or distance
        ensure_collection(base_url, name, size, dist, api_key)

    print("[bootstrap_vectors] Completed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
