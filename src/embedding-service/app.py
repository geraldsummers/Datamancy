"""
Embedding Service using sentence-transformers.
Provides a simple REST API for generating embeddings.
"""

from flask import Flask, request, jsonify
from sentence_transformers import SentenceTransformer
import logging

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Load model (all-MiniLM-L6-v2: 384 dimensions, fast, good quality)
logger.info("Loading sentence-transformers model...")
model = SentenceTransformer('sentence-transformers/all-MiniLM-L6-v2')
logger.info("Model loaded successfully")


@app.route('/health', methods=['GET'])
def health():
    """Health check endpoint."""
    return jsonify({"status": "ok", "model": "all-MiniLM-L6-v2", "dimensions": 384})


@app.route('/embed', methods=['POST'])
def embed():
    """
    Generate embedding for text.

    Request body:
    {
        "text": "Text to embed"
    }

    Response:
    {
        "embedding": [0.123, -0.456, ...],
        "dimensions": 384
    }
    """
    try:
        data = request.get_json()

        if not data or 'text' not in data:
            return jsonify({"error": "Missing 'text' field in request body"}), 400

        text = data['text']

        if not text or not text.strip():
            return jsonify({"error": "Text cannot be empty"}), 400

        # Truncate very long texts (model max is 512 tokens, ~2000 chars)
        if len(text) > 10000:
            logger.warning(f"Text too long ({len(text)} chars), truncating to 10000")
            text = text[:10000]

        # Generate embedding
        logger.debug(f"Generating embedding for text of length {len(text)}")
        embedding = model.encode(text, convert_to_numpy=True)

        return jsonify({
            "embedding": embedding.tolist(),
            "dimensions": len(embedding)
        })

    except Exception as e:
        logger.error(f"Error generating embedding: {str(e)}", exc_info=True)
        return jsonify({"error": str(e)}), 500


@app.route('/embed/batch', methods=['POST'])
def embed_batch():
    """
    Generate embeddings for multiple texts.

    Request body:
    {
        "texts": ["Text 1", "Text 2", ...]
    }

    Response:
    {
        "embeddings": [[0.123, ...], [0.456, ...]],
        "count": 2,
        "dimensions": 384
    }
    """
    try:
        data = request.get_json()

        if not data or 'texts' not in data:
            return jsonify({"error": "Missing 'texts' field in request body"}), 400

        texts = data['texts']

        if not isinstance(texts, list):
            return jsonify({"error": "'texts' must be an array"}), 400

        if len(texts) == 0:
            return jsonify({"error": "texts array cannot be empty"}), 400

        if len(texts) > 100:
            return jsonify({"error": "Maximum 100 texts per batch"}), 400

        # Truncate very long texts
        texts = [t[:10000] if len(t) > 10000 else t for t in texts]

        # Generate embeddings
        logger.info(f"Generating embeddings for {len(texts)} texts")
        embeddings = model.encode(texts, convert_to_numpy=True, show_progress_bar=False)

        return jsonify({
            "embeddings": embeddings.tolist(),
            "count": len(embeddings),
            "dimensions": len(embeddings[0]) if len(embeddings) > 0 else 384
        })

    except Exception as e:
        logger.error(f"Error generating batch embeddings: {str(e)}", exc_info=True)
        return jsonify({"error": str(e)}), 500


@app.route('/', methods=['GET'])
def root():
    """Root endpoint with service info."""
    return jsonify({
        "service": "Datamancy Embedding Service",
        "version": "1.0.0",
        "model": "sentence-transformers/all-MiniLM-L6-v2",
        "dimensions": 384,
        "endpoints": {
            "/health": "GET - Health check",
            "/embed": "POST - Generate single embedding",
            "/embed/batch": "POST - Generate batch embeddings"
        }
    })


if __name__ == '__main__':
    # Run on all interfaces, port 8000
    app.run(host='0.0.0.0', port=8000, debug=False)
