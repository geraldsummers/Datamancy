# Open WebUI Configuration

## Setting Default Model to Qwen

The `DEFAULT_MODELS` environment variable in services.registry.yaml sets `qwen2.5-7b-instruct` as the default model.

## Including Tool Server Prompt

Open WebUI doesn't support default system prompts via environment variables. Instead, configure this through the web UI:

### Option 1: Create a Prompt Template (Recommended)

1. Log into Open WebUI at https://open-webui.${DOMAIN}
2. Go to **Workspace** → **Prompts**
3. Click **+ New Prompt**
4. Set:
   - **Name**: `Qwen with Agent Tools`
   - **Content**: Copy from `/configs/prompts/system/qwen-with-tools.txt`
5. Save and set as default for your user

### Option 2: Create a Custom Model

1. Go to **Workspace** → **Models**
2. Click **+ Create Model**
3. Use this Modelfile:

```
FROM qwen2.5-7b-instruct

SYSTEM """
You are a helpful AI assistant with access to agent tools from the Datamancy stack.

## Available Tool Categories

The agent-tool-server provides the following tools:
- **normalize_whitespace**: Collapse repeated whitespace and trim text
- **uuid_generate**: Generate random UUIDs
- **host_exec_readonly**: Execute safe read-only commands on the host
- **docker_logs**: Fetch container logs
- **docker_restart**: Restart Docker containers

For the complete, up-to-date tool schema, fetch: https://prompts.${DOMAIN}/tools.json

## Communication Guidelines

- Be concise and direct
- Explain your reasoning when using tools
- Ask for clarification if needed
- Show command output when relevant
- Handle errors gracefully
"""

PARAMETER temperature 0.7
PARAMETER top_p 0.9
```

4. Name it `qwen-with-tools` and save
5. Select this model for your chats

### Option 3: Per-Chat Configuration

In any chat:
1. Click the model dropdown
2. Select `qwen2.5-7b-instruct`
3. Click the settings icon
4. Set **System Prompt** to reference https://prompts.${DOMAIN}/system/qwen-with-tools.txt

## Tools JSON Endpoint

Tool definitions are available at:
- https://prompts.${DOMAIN}/tools.json (stack-internal)
- Generated at build time from agent-tool-server

## Default Prompt Suggestions

The `DEFAULT_PROMPT_SUGGESTIONS` env var includes a suggestion to "View available tools" which fetches the tools.json schema.
