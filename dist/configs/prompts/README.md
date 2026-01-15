# Datamancy Prompt Repository

Central repository for prompts, system instructions, and tool schemas used across the stack.

## Structure

```
/tools.json              - Agent tool server schema (dynamically served from agent-tool-server)
/system/                 - System prompts for different agent types
/templates/              - Reusable prompt templates
```

## Access

All prompts are served via HTTPS at:
```
https://prompts.${DOMAIN}/tools.json
https://prompts.${DOMAIN}/system/code-assistant.txt
https://prompts.${DOMAIN}/system/qwen-with-tools.txt
https://prompts.${DOMAIN}/system/data-analyst.txt
```

## Usage

### Open WebUI Tool Calling Integration

Open WebUI supports function calling via tool definitions. The Datamancy agent-tool-server exposes tools in OpenAI-compatible format.

#### 1. Enable Function Calling in Open WebUI

1. Navigate to **Admin Settings** → **Models**
2. Select your model (e.g., `qwen2.5-7b-instruct`)
3. Enable **Function Calling** support
4. Configure the tool endpoint:
   - **Tool Server URL**: `https://prompts.${DOMAIN}/tools.json`
   - **Tool Call Format**: `OpenAI`

#### 2. Using Tools in Chat

Once configured, Open WebUI will automatically:
- Fetch available tools from the endpoint
- Present them to the LLM during conversations
- Execute tool calls via the agent-tool-server
- Return results back to the LLM

Example conversation:
```
User: What containers are currently running?
Assistant: [Uses docker_logs tool to check container status]
```

#### 3. Available Tool Categories

The agent-tool-server provides:
- **Core Tools**: Text processing, UUID generation
- **Host Tools**: Read-only system commands
- **Docker Tools**: Container management
- **Browser Tools**: Web scraping and interaction
- **LLM Tools**: AI model completions
- **Ops Tools**: SSH access to infrastructure
- **Data Source Tools**: Database queries (Postgres, MariaDB, ClickHouse, Qdrant, LDAP)

### From Custom Agents

Fetch tool definitions programmatically:
```python
import requests

# Get full tool schema with metadata
response = requests.get("https://prompts.${DOMAIN}/tools.json")
schema = response.json()

print(f"Available tools: {schema['toolCount']}")
for tool in schema['tools']:
    print(f"  - {tool['name']}: {tool['description']}")
```

### System Prompts

Load system prompts for different agent types:
```bash
# Code assistant with tool awareness
curl https://prompts.${DOMAIN}/system/code-assistant.txt

# Qwen model optimized for tool calling
curl https://prompts.${DOMAIN}/system/qwen-with-tools.txt

# Data analysis focused agent
curl https://prompts.${DOMAIN}/system/data-analyst.txt
```

## Tool Schema Format

Tools are exposed in OpenAI function calling format:
```json
{
  "version": "1.0.0",
  "format": "openai-function-calling",
  "tools": [
    {
      "name": "normalize_whitespace",
      "description": "Collapse repeated whitespace and trim",
      "parameters": {
        "type": "object",
        "required": ["text"],
        "properties": {
          "text": {
            "type": "string",
            "description": "Input text to normalize"
          }
        }
      }
    }
  ]
}
```

## Updating Prompts

1. Edit files in `configs.templates/prompts/`
2. Run `./build-datamancy.main.kts`
3. Redeploy: `docker compose up -d prompt-server`

**Note**: The `tools.json` endpoint is dynamically generated from the agent-tool-server and requires no manual updates.
