#!/bin/bash
# Startup configuration script for Jupyter notebooks
# This script configures LM agent libraries to use litellm/vllm endpoints

# Create .jupyter directory if it doesn't exist
mkdir -p /home/jovyan/.jupyter

# Create Jupyter AI configuration pointing to LiteLLM
cat > /home/jovyan/.jupyter/jupyter_jupyter_ai_config.json <<'EOF'
{
  "AiExtension": {
    "model_parameters": {
      "openai-chat:qwen2.5-0.5b": {
        "api_base": "http://litellm:4000/v1",
        "api_key": "${LITELLM_API_KEY}"
      }
    }
  }
}
EOF

# Create default environment file for notebook sessions
cat > /home/jovyan/.env <<'EOF'
# LiteLLM Configuration
OPENAI_API_BASE=http://litellm:4000/v1
OPENAI_API_KEY=${LITELLM_API_KEY}

# vLLM Direct Configuration (if needed)
VLLM_API_BASE=http://vllm:8000/v1
VLLM_API_KEY=unused

# Default model names
DEFAULT_LLM_MODEL=qwen2.5-0.5b
DEFAULT_EMBEDDING_MODEL=embed-small

# LangChain Configuration
LANGCHAIN_TRACING_V2=false
EOF

# Create welcome notebook with examples
cat > /home/jovyan/LM_Agent_Examples.ipynb <<'EOF'
{
 "cells": [
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "# LM Agent Programming Examples\n",
    "\n",
    "This notebook demonstrates how to use various LM agent frameworks with the Datamancy stack's LiteLLM/vLLM endpoints.\n",
    "\n",
    "## Available Frameworks:\n",
    "- **Jupyter AI**: Native JupyterLab AI assistant with `%%ai` magic commands\n",
    "- **LangChain**: Comprehensive LLM application framework\n",
    "- **LlamaIndex**: RAG and data framework\n",
    "- **AutoGen**: Multi-agent collaboration framework\n",
    "\n",
    "## LiteLLM Endpoint\n",
    "- Base URL: `http://litellm:4000/v1`\n",
    "- Available Models: `qwen2.5-0.5b`, `embed-small`"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 1. Setup - Load Environment Variables"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "import os\n",
    "from dotenv import load_dotenv\n",
    "\n",
    "# Load environment variables\n",
    "load_dotenv()\n",
    "\n",
    "# Display configuration\n",
    "print(f\"LiteLLM API Base: {os.getenv('OPENAI_API_BASE')}\")\n",
    "print(f\"Default Model: {os.getenv('DEFAULT_LLM_MODEL')}\")\n",
    "print(f\"Embedding Model: {os.getenv('DEFAULT_EMBEDDING_MODEL')}\")"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 2. Jupyter AI Magic Commands\n",
    "\n",
    "Use `%%ai` magic commands directly in cells:"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "# First, configure Jupyter AI to use our LiteLLM endpoint\n",
    "# This requires setting up the model provider in JupyterLab's AI extension settings\n",
    "# Go to Settings > Jupyter AI > Model provider and add OpenAI-compatible provider\n",
    "\n",
    "# Example magic command (uncomment after configuration):\n",
    "# %%ai openai-chat:qwen2.5-0.5b\n",
    "# Explain what a Python decorator is with a simple example"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 3. LangChain with LiteLLM"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from langchain_openai import ChatOpenAI\n",
    "from langchain.schema import HumanMessage, SystemMessage\n",
    "\n",
    "# Initialize LangChain with LiteLLM endpoint\n",
    "llm = ChatOpenAI(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    openai_api_base=\"http://litellm:4000/v1\",\n",
    "    openai_api_key=os.getenv(\"OPENAI_API_KEY\", \"unused\"),\n",
    "    temperature=0.7\n",
    ")\n",
    "\n",
    "# Simple chat example\n",
    "messages = [\n",
    "    SystemMessage(content=\"You are a helpful coding assistant.\"),\n",
    "    HumanMessage(content=\"Write a Python function to calculate fibonacci numbers.\")\n",
    "]\n",
    "\n",
    "response = llm.invoke(messages)\n",
    "print(response.content)"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 4. LangChain Agent with Tools"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from langchain.agents import AgentExecutor, create_openai_functions_agent\n",
    "from langchain.tools import tool\n",
    "from langchain.prompts import ChatPromptTemplate, MessagesPlaceholder\n",
    "\n",
    "@tool\n",
    "def calculate_sum(a: int, b: int) -> int:\n",
    "    \"\"\"Calculate the sum of two numbers.\"\"\"\n",
    "    return a + b\n",
    "\n",
    "@tool\n",
    "def calculate_product(a: int, b: int) -> int:\n",
    "    \"\"\"Calculate the product of two numbers.\"\"\"\n",
    "    return a * b\n",
    "\n",
    "tools = [calculate_sum, calculate_product]\n",
    "\n",
    "prompt = ChatPromptTemplate.from_messages([\n",
    "    (\"system\", \"You are a helpful assistant with math tools.\"),\n",
    "    (\"human\", \"{input}\"),\n",
    "    MessagesPlaceholder(variable_name=\"agent_scratchpad\"),\n",
    "])\n",
    "\n",
    "agent = create_openai_functions_agent(llm, tools, prompt)\n",
    "agent_executor = AgentExecutor(agent=agent, tools=tools, verbose=True)\n",
    "\n",
    "result = agent_executor.invoke({\"input\": \"What is (5 + 3) * 4?\"})\n",
    "print(result[\"output\"])"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 5. LlamaIndex with LiteLLM"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from llama_index.llms.openai import OpenAI\n",
    "from llama_index.core import Settings, VectorStoreIndex, SimpleDirectoryReader\n",
    "from llama_index.embeddings.openai import OpenAIEmbedding\n",
    "\n",
    "# Configure LlamaIndex to use LiteLLM\n",
    "Settings.llm = OpenAI(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    api_base=\"http://litellm:4000/v1\",\n",
    "    api_key=\"unused\"\n",
    ")\n",
    "\n",
    "Settings.embed_model = OpenAIEmbedding(\n",
    "    model=\"embed-small\",\n",
    "    api_base=\"http://litellm:4000/v1\",\n",
    "    api_key=\"unused\"\n",
    ")\n",
    "\n",
    "# Simple query example\n",
    "from llama_index.core import Document\n",
    "\n",
    "documents = [\n",
    "    Document(text=\"LlamaIndex is a data framework for LLM applications.\"),\n",
    "    Document(text=\"It helps you ingest, structure, and access private data.\")\n",
    "]\n",
    "\n",
    "index = VectorStoreIndex.from_documents(documents)\n",
    "query_engine = index.as_query_engine()\n",
    "\n",
    "response = query_engine.query(\"What is LlamaIndex?\")\n",
    "print(response)"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 6. AutoGen Multi-Agent Example"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from autogen_agentchat.agents import AssistantAgent\n",
    "from autogen_agentchat.ui import Console\n",
    "from autogen_agentchat.conditions import TextMentionTermination\n",
    "from autogen_agentchat.teams import RoundRobinGroupChat\n",
    "from autogen_ext.models import OpenAIChatCompletionClient\n",
    "\n",
    "# Configure OpenAI-compatible client for LiteLLM\n",
    "model_client = OpenAIChatCompletionClient(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    api_key=\"unused\",\n",
    "    base_url=\"http://litellm:4000/v1\"\n",
    ")\n",
    "\n",
    "# Create agents\n",
    "primary_agent = AssistantAgent(\n",
    "    \"primary_agent\",\n",
    "    model_client=model_client,\n",
    "    system_message=\"You are a helpful assistant that coordinates tasks.\"\n",
    ")\n",
    "\n",
    "critic_agent = AssistantAgent(\n",
    "    \"critic_agent\",\n",
    "    model_client=model_client,\n",
    "    system_message=\"You review and provide constructive feedback on solutions.\"\n",
    ")\n",
    "\n",
    "# Create team\n",
    "termination = TextMentionTermination(\"APPROVE\")\n",
    "team = RoundRobinGroupChat([primary_agent, critic_agent], termination_condition=termination)\n",
    "\n",
    "# Run team chat\n",
    "import asyncio\n",
    "\n",
    "async def run_team():\n",
    "    result = await Console(team.run_stream(task=\"Design a simple REST API for a todo app. Say APPROVE when done.\"))\n",
    "    return result\n",
    "\n",
    "# Execute\n",
    "# result = await run_team()\n",
    "print(\"Multi-agent example ready. Uncomment the last line to execute.\")"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 7. Direct OpenAI Client with LiteLLM"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from openai import OpenAI\n",
    "\n",
    "# Direct OpenAI client configured for LiteLLM\n",
    "client = OpenAI(\n",
    "    base_url=\"http://litellm:4000/v1\",\n",
    "    api_key=\"unused\"\n",
    ")\n",
    "\n",
    "response = client.chat.completions.create(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    messages=[\n",
    "        {\"role\": \"system\", \"content\": \"You are a helpful assistant.\"},\n",
    "        {\"role\": \"user\", \"content\": \"Explain what LiteLLM is in one sentence.\"}\n",
    "    ]\n",
    ")\n",
    "\n",
    "print(response.choices[0].message.content)"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## Next Steps\n",
    "\n",
    "1. Explore the Jupyter AI chat interface in the JupyterLab sidebar\n",
    "2. Build more complex agents with tool calling\n",
    "3. Create RAG applications with your own data\n",
    "4. Experiment with multi-agent workflows\n",
    "\n",
    "For more information:\n",
    "- LangChain: https://python.langchain.com/\n",
    "- LlamaIndex: https://docs.llamaindex.ai/\n",
    "- AutoGen: https://microsoft.github.io/autogen/\n",
    "- Jupyter AI: https://jupyter-ai.readthedocs.io/"
   ]
  }
 ],
 "metadata": {
  "kernelspec": {
   "display_name": "Python 3",
   "language": "python",
   "name": "python3"
  },
  "language_info": {
   "name": "python",
   "version": "3.11.0"
  }
 },
 "nbformat": 4,
 "nbformat_minor": 4
}
EOF

echo "Jupyter notebook environment configured for LM agent programming"
