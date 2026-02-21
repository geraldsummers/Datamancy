# Code Generation Templates

## Docker Compose Service Templates

### Basic Web Service
```yaml
services:
  web-app:
    image: nginx:latest
    container_name: my-web-app
    restart: unless-stopped
    networks:
      - caddy
    labels:
      - "traefik.enable=true"
    environment:
      - APP_ENV=production
    volumes:
      - ./config:/etc/nginx/conf.d:ro
    ports:
      - "8080:80"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost/health"]
      interval: 30s
      timeout: 10s
      retries: 3
```

### Database Service (PostgreSQL)
```yaml
services:
  database:
    image: postgres:15
    container_name: my-database
    restart: unless-stopped
    networks:
      - backend
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - db_data:/var/lib/postgresql/data
      - ./init-db.sql:/docker-entrypoint-initdb.d/init.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  db_data:
    driver: local
```

### Application with Database Dependency
```yaml
services:
  app:
    image: myapp:latest
    container_name: my-app
    restart: unless-stopped
    depends_on:
      database:
        condition: service_healthy
    networks:
      - frontend
      - backend
    environment:
      DATABASE_URL: postgresql://${DB_USER}:${DB_PASSWORD}@database:5432/${DB_NAME}
      REDIS_URL: redis://valkey:6379/0
    ports:
      - "3000:3000"

  database:
    image: postgres:15
    networks:
      - backend
    environment:
      POSTGRES_DB: ${DB_NAME}
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - db_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready"]
      interval: 10s
```

## Python Templates

### Data Analysis Script
```python
#!/usr/bin/env python3
"""
Data Analysis Script Template
"""
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt
from typing import Optional

def load_data(filepath: str) -> pd.DataFrame:
    """Load data from CSV file."""
    try:
        df = pd.read_csv(filepath)
        print(f"Loaded {len(df)} rows from {filepath}")
        return df
    except FileNotFoundError:
        print(f"Error: File not found: {filepath}")
        return pd.DataFrame()
    except Exception as e:
        print(f"Error loading data: {e}")
        return pd.DataFrame()

def clean_data(df: pd.DataFrame) -> pd.DataFrame:
    """Clean and prepare data."""
    # Remove duplicates
    df = df.drop_duplicates()

    # Handle missing values
    df = df.dropna()

    # Convert types if needed
    # df['date'] = pd.to_datetime(df['date'])
    # df['value'] = df['value'].astype(float)

    print(f"After cleaning: {len(df)} rows")
    return df

def analyze_data(df: pd.DataFrame) -> dict:
    """Perform analysis and return summary statistics."""
    results = {
        'count': len(df),
        'numeric_summary': df.describe().to_dict(),
        # Add custom analysis here
    }
    return results

def visualize_data(df: pd.DataFrame, output_path: Optional[str] = None):
    """Create visualizations."""
    fig, axes = plt.subplots(2, 2, figsize=(12, 10))

    # Plot 1: Line chart
    df.plot(x='x_column', y='y_column', ax=axes[0, 0], title='Time Series')

    # Plot 2: Bar chart
    df['category'].value_counts().plot(kind='bar', ax=axes[0, 1], title='Category Distribution')

    # Plot 3: Histogram
    df['value'].hist(bins=30, ax=axes[1, 0], title='Value Distribution')

    # Plot 4: Scatter plot
    axes[1, 1].scatter(df['x'], df['y'], alpha=0.5)
    axes[1, 1].set_title('X vs Y')

    plt.tight_layout()

    if output_path:
        plt.savefig(output_path, dpi=300, bbox_inches='tight')
        print(f"Visualization saved to {output_path}")
    else:
        plt.show()

def main():
    """Main execution function."""
    # Load data
    df = load_data('data.csv')
    if df.empty:
        return

    # Clean data
    df = clean_data(df)

    # Analyze
    results = analyze_data(df)
    print("\nAnalysis Results:")
    print(results)

    # Visualize
    visualize_data(df, output_path='output.png')

if __name__ == "__main__":
    main()
```

### API Client
```python
"""
API Client Template
"""
import requests
from typing import Dict, Optional, Any
import json

class APIClient:
    """Client for interacting with REST API."""

    def __init__(self, base_url: str, api_key: Optional[str] = None):
        """Initialize API client."""
        self.base_url = base_url.rstrip('/')
        self.session = requests.Session()

        if api_key:
            self.session.headers.update({
                'Authorization': f'Bearer {api_key}',
                'Content-Type': 'application/json'
            })

    def get(self, endpoint: str, params: Optional[Dict] = None) -> Dict[str, Any]:
        """Make GET request."""
        url = f"{self.base_url}/{endpoint.lstrip('/')}"
        try:
            response = self.session.get(url, params=params, timeout=30)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            print(f"GET request failed: {e}")
            return {}

    def post(self, endpoint: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """Make POST request."""
        url = f"{self.base_url}/{endpoint.lstrip('/')}"
        try:
            response = self.session.post(url, json=data, timeout=30)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            print(f"POST request failed: {e}")
            return {}

    def put(self, endpoint: str, data: Dict[str, Any]) -> Dict[str, Any]:
        """Make PUT request."""
        url = f"{self.base_url}/{endpoint.lstrip('/')}"
        try:
            response = self.session.put(url, json=data, timeout=30)
            response.raise_for_status()
            return response.json()
        except requests.exceptions.RequestException as e:
            print(f"PUT request failed: {e}")
            return {}

    def delete(self, endpoint: str) -> bool:
        """Make DELETE request."""
        url = f"{self.base_url}/{endpoint.lstrip('/')}"
        try:
            response = self.session.delete(url, timeout=30)
            response.raise_for_status()
            return True
        except requests.exceptions.RequestException as e:
            print(f"DELETE request failed: {e}")
            return False

# Usage example
if __name__ == "__main__":
    client = APIClient("https://api.example.com", api_key="your-api-key")

    # GET request
    data = client.get("/users", params={"limit": 10})
    print(data)

    # POST request
    new_user = client.post("/users", data={"name": "John", "email": "john@example.com"})
    print(new_user)
```

### Database Query Script
```python
"""
Database Query Template
"""
import psycopg2
from psycopg2.extras import RealDictCursor
from typing import List, Dict, Any, Optional
import os

class DatabaseClient:
    """PostgreSQL database client."""

    def __init__(self, host: str = "postgres", port: int = 5432,
                 database: str = "mydb", user: str = "user",
                 password: Optional[str] = None):
        """Initialize database connection."""
        self.conn = psycopg2.connect(
            host=host,
            port=port,
            database=database,
            user=user,
            password=password or os.getenv("DB_PASSWORD")
        )

    def query(self, sql: str, params: Optional[tuple] = None) -> List[Dict[str, Any]]:
        """Execute SELECT query and return results."""
        with self.conn.cursor(cursor_factory=RealDictCursor) as cursor:
            cursor.execute(sql, params)
            return [dict(row) for row in cursor.fetchall()]

    def execute(self, sql: str, params: Optional[tuple] = None) -> int:
        """Execute INSERT/UPDATE/DELETE query."""
        with self.conn.cursor() as cursor:
            cursor.execute(sql, params)
            self.conn.commit()
            return cursor.rowcount

    def close(self):
        """Close database connection."""
        self.conn.close()

# Usage example
if __name__ == "__main__":
    db = DatabaseClient(
        host="postgres",
        database="mydb",
        user="myuser",
        password=os.getenv("DB_PASSWORD")
    )

    # Query example
    users = db.query("SELECT * FROM users WHERE active = %s", (True,))
    for user in users:
        print(f"{user['name']}: {user['email']}")

    # Insert example
    rows_affected = db.execute(
        "INSERT INTO users (name, email) VALUES (%s, %s)",
        ("John Doe", "john@example.com")
    )
    print(f"Inserted {rows_affected} rows")

    db.close()
```

## Qdrant Integration Templates

### Vector Search Client
```python
"""
Qdrant Vector Search Client Template
"""
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct, Filter, FieldCondition, MatchValue
from typing import List, Dict, Any
import uuid

class VectorSearchClient:
    """Client for Qdrant vector database."""

    def __init__(self, url: str = "http://qdrant:6333"):
        """Initialize Qdrant client."""
        self.client = QdrantClient(url=url)

    def create_collection(self, collection_name: str, vector_size: int = 1024):
        """Create a new collection."""
        self.client.create_collection(
            collection_name=collection_name,
            vectors_config=VectorParams(
                size=vector_size,
                distance=Distance.COSINE
            )
        )
        print(f"Created collection: {collection_name}")

    def insert_vectors(self, collection_name: str, vectors: List[List[float]],
                      payloads: List[Dict[str, Any]]):
        """Insert vectors with metadata."""
        points = [
            PointStruct(
                id=str(uuid.uuid4()),
                vector=vector,
                payload=payload
            )
            for vector, payload in zip(vectors, payloads)
        ]

        self.client.upsert(
            collection_name=collection_name,
            points=points
        )
        print(f"Inserted {len(points)} vectors")

    def search(self, collection_name: str, query_vector: List[float],
              limit: int = 10, score_threshold: float = 0.7) -> List[Dict[str, Any]]:
        """Search for similar vectors."""
        results = self.client.search(
            collection_name=collection_name,
            query_vector=query_vector,
            limit=limit,
            score_threshold=score_threshold
        )

        return [
            {
                'id': result.id,
                'score': result.score,
                'payload': result.payload
            }
            for result in results
        ]

    def search_with_filter(self, collection_name: str, query_vector: List[float],
                          filter_field: str, filter_value: str,
                          limit: int = 10) -> List[Dict[str, Any]]:
        """Search with payload filtering."""
        results = self.client.search(
            collection_name=collection_name,
            query_vector=query_vector,
            query_filter=Filter(
                must=[
                    FieldCondition(
                        key=filter_field,
                        match=MatchValue(value=filter_value)
                    )
                ]
            ),
            limit=limit
        )

        return [
            {
                'id': result.id,
                'score': result.score,
                'payload': result.payload
            }
            for result in results
        ]

# Usage example
if __name__ == "__main__":
    client = VectorSearchClient("http://qdrant:6333")

    # Assume you have an embedding function
    def embed(text: str) -> List[float]:
        # This would call your actual embedding model
        return [0.1] * 1024

    # Insert documents
    documents = [
        "Docker containers are lightweight",
        "Kubernetes orchestrates containers",
        "Microservices architecture benefits"
    ]

    vectors = [embed(doc) for doc in documents]
    payloads = [{'text': doc, 'category': 'containers'} for doc in documents]

    client.insert_vectors('documents', vectors, payloads)

    # Search
    query = "container orchestration"
    query_vector = embed(query)
    results = client.search('documents', query_vector, limit=5)

    for result in results:
        print(f"Score: {result['score']:.3f}")
        print(f"Text: {result['payload']['text']}")
        print("---")
```

## Search Service Integration Template

```python
"""
Datamancy Search Service Integration
"""
import requests
from typing import List, Dict, Any, Optional

class SearchService:
    """Client for Datamancy Search Service."""

    def __init__(self, base_url: str = "http://search-service:8098"):
        """Initialize search client."""
        self.base_url = base_url

    def search(self, query: str, mode: str = "hybrid",
              collections: Optional[List[str]] = None,
              limit: int = 10, min_score: float = 0.0) -> List[Dict[str, Any]]:
        """Search across collections."""
        payload = {
            "query": query,
            "mode": mode,
            "collections": collections or ["*"],
            "limit": limit,
            "min_score": min_score
        }

        try:
            response = requests.post(
                f"{self.base_url}/search",
                json=payload,
                timeout=30
            )
            response.raise_for_status()
            data = response.json()
            return data.get("results", [])
        except requests.exceptions.RequestException as e:
            print(f"Search failed: {e}")
            return []

    def get_context_for_rag(self, query: str, max_results: int = 5) -> str:
        """Get formatted context for RAG applications."""
        results = self.search(query, mode="hybrid", limit=max_results)

        context_parts = []
        for i, result in enumerate(results, 1):
            context_parts.append(
                f"[Source {i}] {result.get('title', 'Untitled')} "
                f"({result.get('source', 'unknown')})\n"
                f"{result.get('content', '')}\n"
            )

        return "\n".join(context_parts)

# Usage example
if __name__ == "__main__":
    search = SearchService()

    # Simple search
    results = search.search("docker compose networking", mode="hybrid", limit=5)
    for result in results:
        print(f"[{result['score']:.2f}] {result['title']}")
        print(f"  {result['content'][:100]}...")
        print()

    # RAG context
    context = search.get_context_for_rag("how to configure volumes in docker compose")
    print("Context for LLM:\n", context)
```

## Bash Script Templates

### Service Health Check
```bash
#!/bin/bash
# Service health check script

SERVICE_NAME="my-service"
MAX_RETRIES=30
RETRY_INTERVAL=2

echo "Waiting for $SERVICE_NAME to be healthy..."

for i in $(seq 1 $MAX_RETRIES); do
    if docker compose ps $SERVICE_NAME | grep -q "healthy"; then
        echo "✓ $SERVICE_NAME is healthy"
        exit 0
    fi

    echo "Attempt $i/$MAX_RETRIES: $SERVICE_NAME not healthy yet..."
    sleep $RETRY_INTERVAL
done

echo "✗ $SERVICE_NAME failed to become healthy after $MAX_RETRIES attempts"
docker compose logs --tail=50 $SERVICE_NAME
exit 1
```

### Database Backup
```bash
#!/bin/bash
# PostgreSQL backup script

DB_CONTAINER="postgres"
DB_NAME="${1:-mydb}"
BACKUP_DIR="/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="${BACKUP_DIR}/${DB_NAME}_${TIMESTAMP}.sql.gz"

echo "Backing up database: $DB_NAME"

# Create backup directory if it doesn't exist
mkdir -p "$BACKUP_DIR"

# Perform backup
docker exec $DB_CONTAINER pg_dump -U postgres $DB_NAME | gzip > "$BACKUP_FILE"

if [ $? -eq 0 ]; then
    echo "✓ Backup successful: $BACKUP_FILE"

    # Clean up old backups (keep last 7 days)
    find "$BACKUP_DIR" -name "${DB_NAME}_*.sql.gz" -mtime +7 -delete
    echo "✓ Old backups cleaned up"
else
    echo "✗ Backup failed"
    exit 1
fi
```

These templates provide ready-to-use code patterns that the AI can reference and adapt for specific use cases in the Datamancy stack.
