#!/usr/bin/env python3
"""
Test vLLM completions via LiteLLM proxy.
"""
import os
from openai import OpenAI

# Configuration
LITELLM_BASE_URL = os.getenv("LITELLM_BASE_URL", "http://localhost:4000/v1")
LITELLM_API_KEY = os.getenv("LITELLM_MASTER_KEY", "sk-1234")

# Models to test
MODELS = [
    "hermes-2-pro-mistral-7b",
    "qwen-code",
    "router",
]

def test_completion(client, model_name):
    """Test a single completion with a model."""
    print(f"\n{'='*60}")
    print(f"Testing model: {model_name}")
    print('='*60)

    try:
        response = client.chat.completions.create(
            model=model_name,
            messages=[
                {"role": "system", "content": "You are a helpful assistant."},
                {"role": "user", "content": "Say 'Hello, World!' and explain what you are in one sentence."}
            ],
            max_tokens=150,
            temperature=0.7
        )

        content = response.choices[0].message.content
        print(f"\n✓ Success!")
        print(f"Response: {content}")
        print(f"Model: {response.model}")
        print(f"Usage: {response.usage}")
        return True

    except Exception as e:
        print(f"\n✗ Failed: {e}")
        return False

def test_streaming_completion(client, model_name):
    """Test streaming completion."""
    print(f"\n{'='*60}")
    print(f"Testing streaming with model: {model_name}")
    print('='*60)

    try:
        stream = client.chat.completions.create(
            model=model_name,
            messages=[
                {"role": "user", "content": "Count from 1 to 5."}
            ],
            max_tokens=50,
            stream=True
        )

        print("\n✓ Streaming response:")
        for chunk in stream:
            if chunk.choices[0].delta.content is not None:
                print(chunk.choices[0].delta.content, end='', flush=True)
        print("\n")
        return True

    except Exception as e:
        print(f"\n✗ Failed: {e}")
        return False

def main():
    print("vLLM Completions Test via LiteLLM")
    print(f"Base URL: {LITELLM_BASE_URL}")
    print(f"API Key: {LITELLM_API_KEY[:10]}..." if len(LITELLM_API_KEY) > 10 else f"API Key: {LITELLM_API_KEY}")

    # Initialize OpenAI client pointing to LiteLLM
    client = OpenAI(
        base_url=LITELLM_BASE_URL,
        api_key=LITELLM_API_KEY
    )

    # Test available models
    print("\n" + "="*60)
    print("Fetching available models...")
    print("="*60)
    try:
        models = client.models.list()
        print("\nAvailable models:")
        for model in models.data:
            print(f"  - {model.id}")
    except Exception as e:
        print(f"Failed to fetch models: {e}")

    # Test each model
    results = {}
    for model in MODELS:
        success = test_completion(client, model)
        results[model] = success

    # Test streaming with one model
    if results.get("hermes-2-pro-mistral-7b"):
        test_streaming_completion(client, "hermes-2-pro-mistral-7b")

    # Summary
    print("\n" + "="*60)
    print("Test Summary")
    print("="*60)
    for model, success in results.items():
        status = "✓ PASS" if success else "✗ FAIL"
        print(f"{status} - {model}")

if __name__ == "__main__":
    main()
