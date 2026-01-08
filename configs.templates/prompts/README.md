# Datamancy Prompt Repository

Central repository for prompts, system instructions, and tool schemas used across the stack.

## Structure

```
/tools.json              - Agent tool server schema (auto-generated at build)
/system/                 - System prompts for different agent types
/templates/              - Reusable prompt templates
```

## Access

All prompts are served via HTTPS at:
```
https://prompts.${DOMAIN}/tools.json
https://prompts.${DOMAIN}/system/code-assistant.txt
```

## Usage

### From Open WebUI
Create a custom model with system prompt:
```bash
curl https://prompts.project-saturn.com/system/code-assistant.txt
```

### From agents
Fetch tool definitions:
```python
import requests
tools = requests.get("https://prompts.project-saturn.com/tools.json").json()
```

## Updating Prompts

1. Edit files in `configs.templates/prompts/`
2. Run `./build-datamancy.main.kts`
3. Redeploy: `docker compose up -d prompt-server`
