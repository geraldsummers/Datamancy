# Custom Jupyter Notebook Image

This directory contains the Dockerfile for a custom Jupyter notebook image that includes multiple programming kernels and comprehensive LM agent programming capabilities.

## Features

### Programming Kernels

1. **Python 3** (default) - Pre-installed in the base jupyter/minimal-notebook image
2. **JavaScript** (ijavascript) - Node.js 20.x with full ES2024+ support
3. **Kotlin** (kotlin-jupyter-kernel) - Kotlin 2.1.0 with Java 17

### LM Agent Programming Frameworks

The image includes comprehensive AI/LLM agent programming libraries pre-configured to work with the Datamancy stack's LiteLLM/vLLM endpoints:

#### 1. Jupyter AI
- **Native JupyterLab AI assistant** with chat interface
- **`%%ai` magic commands** for inline AI interactions
- Pre-configured to use LiteLLM endpoint (`http://litellm:4000/v1`)
- Access via JupyterLab sidebar or magic commands

#### 2. LangChain
- **Comprehensive LLM application framework**
- Build chains, agents, and complex workflows
- Tool calling and function execution
- Includes LangGraph for advanced agent orchestration
- Pre-configured OpenAI-compatible client for LiteLLM

#### 3. LlamaIndex
- **RAG (Retrieval-Augmented Generation) framework**
- Ingest and index documents from multiple sources
- Vector store integration (ChromaDB, FAISS included)
- Query engines and chat interfaces
- Pre-configured for LiteLLM LLM and embedding endpoints

#### 4. AutoGen
- **Multi-agent collaboration framework**
- Create teams of specialized AI agents
- Agent-to-agent communication and task delegation
- Code execution and tool use capabilities
- Pre-configured for LiteLLM endpoint

### Additional Libraries

- **OpenAI Python SDK** - Direct API access to LiteLLM
- **ChromaDB** - Vector database for embeddings
- **FAISS** - Facebook AI Similarity Search for vector operations
- **Sentence Transformers** - Local embedding models
- **TikToken** - Token counting utilities
- **httpx, pydantic, python-dotenv** - Utility libraries

## Configuration

### LiteLLM Endpoint

All frameworks are pre-configured to use the Datamancy stack's LiteLLM proxy:

- **Base URL**: `http://litellm:4000/v1`
- **API Key**: Automatically provided via `LITELLM_MASTER_KEY`
- **Available Models**:
  - `qwen2.5-0.5b` - LLM for chat and completion
  - `embed-small` - Embedding model (sentence-transformers/all-MiniLM-L6-v2)

### Environment Variables

The following environment variables are automatically configured in spawned notebook containers:

```bash
OPENAI_API_BASE=http://litellm:4000/v1
OPENAI_API_KEY=<your-litellm-master-key>
LITELLM_API_KEY=<your-litellm-master-key>
DEFAULT_LLM_MODEL=qwen2.5-0.5b
DEFAULT_EMBEDDING_MODEL=embed-small
```

### Startup Configuration

On notebook container startup, the `startup-config.sh` script automatically:

1. Creates Jupyter AI configuration pointing to LiteLLM
2. Sets up environment variables for all frameworks
3. Creates a welcome notebook (`LM_Agent_Examples.ipynb`) with examples

## Usage Examples

### Jupyter AI Magic Command

```python
%%ai openai-chat:qwen2.5-0.5b
Explain what a Python generator is with a code example
```

### LangChain

```python
from langchain_openai import ChatOpenAI

llm = ChatOpenAI(
    model="qwen2.5-0.5b",
    openai_api_base="http://litellm:4000/v1",
    openai_api_key=os.getenv("LITELLM_API_KEY")
)

response = llm.invoke("Write a fibonacci function")
print(response.content)
```

### LlamaIndex

```python
from llama_index.llms.openai import OpenAI
from llama_index.core import Settings

Settings.llm = OpenAI(
    model="qwen2.5-0.5b",
    api_base="http://litellm:4000/v1",
    api_key="unused"
)

# Build RAG applications with your data
```

### AutoGen Multi-Agent

```python
from autogen_ext.models import OpenAIChatCompletionClient

model_client = OpenAIChatCompletionClient(
    model="qwen2.5-0.5b",
    api_key="unused",
    base_url="http://litellm:4000/v1"
)

# Create multi-agent teams
```

### Direct OpenAI Client

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://litellm:4000/v1",
    api_key="unused"
)

response = client.chat.completions.create(
    model="qwen2.5-0.5b",
    messages=[{"role": "user", "content": "Hello!"}]
)
```

## Persistence Through Obliterate

The custom notebook image is built as part of the docker-compose stack and is tagged as `datamancy-jupyter-notebook:latest`.

**Important:** When running `./stack-controller obliterate`, this image will be removed along with other locally built images. However, when you run `./stack-controller up` again, the image will be automatically rebuilt from this Dockerfile before JupyterHub starts.

The `jupyter-notebook-builder` service in docker-compose.yml ensures the image is built before JupyterHub starts, so all kernels and LM agent libraries will always be available.

## Getting Started

1. Start the Datamancy stack: `./stack-controller up`
2. Access JupyterHub at: `https://jupyterhub.<your-domain>`
3. Log in with your SSO credentials
4. Open the `LM_Agent_Examples.ipynb` notebook for interactive examples
5. Create new notebooks and select your preferred kernel (Python, JavaScript, or Kotlin)

## Jupyter AI Chat Interface

JupyterLab includes a native AI chat interface in the sidebar:

1. Click the chat icon in the left sidebar
2. Configure the OpenAI-compatible provider:
   - Provider: OpenAI
   - Base URL: `http://litellm:4000/v1`
   - Model: `qwen2.5-0.5b`
3. Start chatting with the AI assistant

## Customization

To add more kernels, packages, or LM agent libraries:

1. Edit the `Dockerfile` in this directory
2. Run: `./stack-controller down && ./stack-controller up`
3. The image will be automatically rebuilt with your changes

## Resources

- **Jupyter AI**: https://jupyter-ai.readthedocs.io/
- **LangChain**: https://python.langchain.com/
- **LlamaIndex**: https://docs.llamaindex.ai/
- **AutoGen**: https://microsoft.github.io/autogen/
- **LiteLLM**: https://docs.litellm.ai/
