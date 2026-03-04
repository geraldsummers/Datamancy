#!/usr/bin/env python3
"""
Bootstrap Stack Knowledge Base

Vectorizes markdown documentation and loads into Qdrant for AI context retrieval.
"""
import os
import re
import json
import requests
from pathlib import Path
from typing import List, Dict
import uuid

# Configuration
QDRANT_URL = os.getenv("QDRANT_URL", "http://qdrant:6333")
QDRANT_API_KEY = os.getenv("QDRANT_API_KEY")
EMBEDDING_URL = os.getenv("EMBEDDING_URL", "http://embedding-service:8080")
COLLECTION_NAME = "stack_knowledge"
VECTOR_SIZE = 1024  # bge-m3
KNOWLEDGE_DIR = Path(__file__).parent

def create_collection():
    """Create or recreate Qdrant collection"""
    print(f"Creating collection: {COLLECTION_NAME}")

    headers = {}
    if QDRANT_API_KEY:
        headers["api-key"] = QDRANT_API_KEY

    # Delete existing
    try:
        requests.delete(f"{QDRANT_URL}/collections/{COLLECTION_NAME}", headers=headers)
        print("Deleted existing collection")
    except:
        print("No existing collection to delete")

    # Create new
    response = requests.put(
        f"{QDRANT_URL}/collections/{COLLECTION_NAME}",
        headers=headers,
        json={
            "vectors": {
                "size": VECTOR_SIZE,
                "distance": "Cosine"
            }
        }
    )

    if response.status_code in [200, 201]:
        print("✓ Collection created successfully")
    else:
        print(f"✗ Failed to create collection: {response.text}")
        raise Exception("Collection creation failed")

def generate_embedding(text: str) -> List[float]:
    """Generate embedding using bge-m3 model"""
    response = requests.post(
        f"{EMBEDDING_URL}/embed",
        json={"inputs": text}
    )

    if response.status_code != 200:
        raise Exception(f"Embedding failed: {response.text}")

    # text-embeddings-inference returns a list of embeddings (one per input)
    result = response.json()
    return result[0] if isinstance(result, list) else result

def parse_markdown(filepath: Path) -> List[Dict]:
    """Parse markdown file into chunks"""
    content = filepath.read_text()
    filename = filepath.name
    category = filename.split("-")[0]
    title = " ".join(filename.replace(".md", "").split("-")[1:]).title()

    chunks = []
    lines = content.split("\n")

    current_section = ""
    current_content = []
    chunk_index = 0

    for line in lines:
        if line.startswith("# "):
            # Save previous chunk
            if current_content:
                chunks.append({
                    "title": title,
                    "category": category,
                    "section": current_section,
                    "content": "\n".join(current_content).strip(),
                    "filepath": filename,
                    "chunk_index": chunk_index
                })
                chunk_index += 1
                current_content = []
            current_section = line.replace("# ", "").strip()

        elif line.startswith("## ") or line.startswith("### "):
            # Save if substantial
            if len("\n".join(current_content)) > 500:
                chunks.append({
                    "title": title,
                    "category": category,
                    "section": current_section,
                    "content": "\n".join(current_content).strip(),
                    "filepath": filename,
                    "chunk_index": chunk_index
                })
                chunk_index += 1
                current_content = []
            current_section = line.replace("###", "").replace("##", "").strip()
            current_content.append(line)

        else:
            current_content.append(line)

            # Chunk if too large
            if len("\n".join(current_content)) > 2000:
                chunks.append({
                    "title": title,
                    "category": category,
                    "section": current_section,
                    "content": "\n".join(current_content).strip(),
                    "filepath": filename,
                    "chunk_index": chunk_index
                })
                chunk_index += 1
                current_content = []

    # Add final chunk
    if current_content:
        chunks.append({
            "title": title,
            "category": category,
            "section": current_section,
            "content": "\n".join(current_content).strip(),
            "filepath": filename,
            "chunk_index": chunk_index
        })

    return chunks

def ingest_documents():
    """Process and ingest all markdown files"""
    md_files = sorted([f for f in KNOWLEDGE_DIR.glob("*.md") if re.match(r"\d+-.*\.md", f.name)])

    if not md_files:
        print(f"✗ No markdown files found in {KNOWLEDGE_DIR}")
        return

    print(f"\nFound {len(md_files)} knowledge documents")

    all_points = []
    total_chunks = 0

    for filepath in md_files:
        print(f"\nProcessing: {filepath.name}")
        chunks = parse_markdown(filepath)
        print(f"  Extracted {len(chunks)} chunks")

        for chunk in chunks:
            # Generate embedding
            text = f"{chunk['title']} - {chunk['section']}\n\n{chunk['content']}"
            embedding = generate_embedding(text)

            # Create point
            all_points.append({
                "id": str(uuid.uuid4()),
                "vector": embedding,
                "payload": chunk
            })

            total_chunks += 1
            if total_chunks % 10 == 0:
                print(".", end="", flush=True)

        print(f"\n  ✓ Processed {len(chunks)} chunks")

    # Upsert in batches
    print(f"\nUpserting {total_chunks} points to Qdrant...")
    batch_size = 50

    headers = {}
    if QDRANT_API_KEY:
        headers["api-key"] = QDRANT_API_KEY

    for i in range(0, len(all_points), batch_size):
        batch = all_points[i:i+batch_size]
        response = requests.put(
            f"{QDRANT_URL}/collections/{COLLECTION_NAME}/points",
            headers=headers,
            json={"points": batch}
        )

        if response.status_code in [200, 201]:
            print(f"  ✓ Batch {i//batch_size + 1}/{(total_chunks + batch_size - 1)//batch_size} uploaded")
        else:
            print(f"  ✗ Batch failed: {response.text}")

    print(f"\n✓ Successfully ingested {total_chunks} knowledge chunks")

def verify_collection():
    """Verify collection was created"""
    print("\nVerifying collection...")
    headers = {}
    if QDRANT_API_KEY:
        headers["api-key"] = QDRANT_API_KEY
    response = requests.get(f"{QDRANT_URL}/collections/{COLLECTION_NAME}", headers=headers)

    if response.status_code == 200:
        info = response.json()
        print(f"✓ Collection info:")
        print(f"  Points: {info['result'].get('points_count', 0)}")
        print(f"  Vectors: {info['result'].get('vectors_count', 0)}")
    else:
        print("✗ Failed to get collection info")

def main():
    print("╔" + "="*40 + "╗")
    print("║  Stack Knowledge Bootstrap" + " "*13 + "║")
    print("║  Vectorizing documentation for AI" + " "*6 + "║")
    print("╚" + "="*40 + "╝\n")

    print("Configuration:")
    print(f"  Qdrant: {QDRANT_URL}")
    print(f"  Embedding: {EMBEDDING_URL}")
    print(f"  Collection: {COLLECTION_NAME}")
    print(f"  Knowledge Dir: {KNOWLEDGE_DIR}\n")

    try:
        # Step 1: Create collection
        create_collection()

        # Step 2: Ingest documents
        ingest_documents()

        # Step 3: Verify
        verify_collection()

        print("\n✓ Stack knowledge base ready for AI context retrieval!")

    except Exception as e:
        print(f"\n✗ Error: {e}")
        import traceback
        traceback.print_exc()

if __name__ == "__main__":
    main()
