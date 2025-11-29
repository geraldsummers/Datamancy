#!/bin/bash

# Test vLLM completions via LiteLLM using docker exec

echo "==============================================="
echo "vLLM Completions Test via LiteLLM (Docker)"
echo "==============================================="

# Get the master key from environment
MASTER_KEY="${LITELLM_MASTER_KEY:-sk-1234}"

echo ""
echo "1. Testing LiteLLM /models endpoint..."
echo "---------------------------------------"
docker exec litellm python3 -c "
import urllib.request
import json
import sys

try:
    req = urllib.request.Request('http://localhost:4000/models')
    req.add_header('Authorization', 'Bearer $MASTER_KEY')
    with urllib.request.urlopen(req, timeout=10) as response:
        data = json.loads(response.read().decode())
        print('Available models:')
        for model in data.get('data', []):
            print(f\"  - {model.get('id')}\")
except Exception as e:
    print(f'Error: {e}', file=sys.stderr)
    sys.exit(1)
"

echo ""
echo "2. Testing completion with hermes-2-pro-mistral-7b..."
echo "-----------------------------------------------------"
docker exec litellm python3 -c "
import urllib.request
import json
import sys

try:
    # Prepare request
    data = {
        'model': 'hermes-2-pro-mistral-7b',
        'messages': [
            {'role': 'system', 'content': 'You are a helpful assistant.'},
            {'role': 'user', 'content': 'Say \"Hello, World!\" and explain what you are in one sentence.'}
        ],
        'max_tokens': 150,
        'temperature': 0.7
    }

    req = urllib.request.Request(
        'http://localhost:4000/v1/chat/completions',
        data=json.dumps(data).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': 'Bearer $MASTER_KEY'
        }
    )

    with urllib.request.urlopen(req, timeout=60) as response:
        result = json.loads(response.read().decode())
        content = result['choices'][0]['message']['content']
        print(f'✓ Success!')
        print(f'Response: {content}')
        print(f\"Model: {result.get('model', 'N/A')}\")
        print(f\"Usage: {result.get('usage', {})}\")
except Exception as e:
    print(f'✗ Failed: {e}', file=sys.stderr)
    import traceback
    traceback.print_exc(file=sys.stderr)
    sys.exit(1)
"

echo ""
echo "3. Testing completion with qwen-code..."
echo "----------------------------------------"
docker exec litellm python3 -c "
import urllib.request
import json
import sys

try:
    data = {
        'model': 'qwen-code',
        'messages': [
            {'role': 'user', 'content': 'Write a Python function to calculate factorial.'}
        ],
        'max_tokens': 200
    }

    req = urllib.request.Request(
        'http://localhost:4000/v1/chat/completions',
        data=json.dumps(data).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': 'Bearer $MASTER_KEY'
        }
    )

    with urllib.request.urlopen(req, timeout=60) as response:
        result = json.loads(response.read().decode())
        content = result['choices'][0]['message']['content']
        print(f'✓ Success!')
        print(f'Response: {content[:200]}...' if len(content) > 200 else f'Response: {content}')
except Exception as e:
    print(f'✗ Failed: {e}', file=sys.stderr)
    sys.exit(1)
"

echo ""
echo "4. Testing completion with router..."
echo "-------------------------------------"
docker exec litellm python3 -c "
import urllib.request
import json
import sys

try:
    data = {
        'model': 'router',
        'messages': [
            {'role': 'user', 'content': 'What is 2+2?'}
        ],
        'max_tokens': 50
    }

    req = urllib.request.Request(
        'http://localhost:4000/v1/chat/completions',
        data=json.dumps(data).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'Authorization': 'Bearer $MASTER_KEY'
        }
    )

    with urllib.request.urlopen(req, timeout=60) as response:
        result = json.loads(response.read().decode())
        content = result['choices'][0]['message']['content']
        print(f'✓ Success!')
        print(f'Response: {content}')
except Exception as e:
    print(f'✗ Failed: {e}', file=sys.stderr)
    sys.exit(1)
"

echo ""
echo "==============================================="
echo "Test Complete"
echo "==============================================="
