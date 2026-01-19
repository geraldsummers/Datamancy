#!/bin/bash
# Example script showing how Qwen can call agent-tool-server tools
# These same calls can be made by Qwen through any API client

TOOL_SERVER="http://agent-tool-server:8081"

echo "Qwen Tool Usage Examples"
echo "========================"
echo

# Example 1: Get Docker logs
echo "1. Getting logs from search-service (last 50 lines)"
curl -s -X POST "${TOOL_SERVER}/call-tool" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "docker_logs",
    "arguments": {
      "container": "search-service",
      "tail": 50
    }
  }' | jq -r '.logs' | head -20
echo "..."
echo

# Example 2: List running containers
echo "2. Listing all running containers"
curl -s -X POST "${TOOL_SERVER}/call-tool" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "docker_ps",
    "arguments": {}
  }' | jq -r '.output' | head -10
echo "..."
echo

# Example 3: Query PostgreSQL
echo "3. Querying PostgreSQL for recent fetch history"
curl -s -X POST "${TOOL_SERVER}/call-tool" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "query_postgres",
    "arguments": {
      "database": "datamancy",
      "query": "SELECT source, COUNT(*) as count FROM fetch_history WHERE fetched_at > NOW() - INTERVAL '"'"'24 hours'"'"' GROUP BY source ORDER BY count DESC LIMIT 5"
    }
  }' | jq -r '.result'
echo

# Example 4: Read documentation file
echo "4. Reading stack documentation"
curl -s -X POST "${TOOL_SERVER}/call-tool" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ssh_read_file",
    "arguments": {
      "path": "~/QWEN_STACK_ASSISTANT_GUIDE.md"
    }
  }' | jq -r '.content' | head -30
echo "..."
echo

# Example 5: Execute whitelisted SSH command
echo "5. Getting vLLM logs via SSH (whitelisted)"
curl -s -X POST "${TOOL_SERVER}/call-tool" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ssh_exec_whitelisted",
    "arguments": {
      "cmd": "docker logs vllm-7b --tail 20"
    }
  }' | jq -r '.stdout' | head -20
echo "..."
echo

echo "These are the same tools Qwen can use to monitor and manage the stack!"
