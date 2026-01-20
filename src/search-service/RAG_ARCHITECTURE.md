# Search Service as Universal RAG Provider

## Vision
The search-service is the **unified RAG provider** for the entire Datamancy stack, serving both human users and AI agents with intelligent result routing.

## Dual Audience Architecture

### Human Users
- **Need:** Rich UI, visualizations, interactive elements, context
- **Get:** Modals, charts, inline previews, "Chat with AI" buttons
- **Interface:** Web UI at `search-service:8098/`

### AI Agents
- **Need:** Structured data, raw content, APIs, metadata
- **Get:** JSON responses, full content, structured fields
- **Interface:** HTTP API at `search-service:8098/search`

## Content Capabilities System

Every search result includes capability flags:

```kotlin
ContentCapabilities(
    humanFriendly: Boolean,      // Has UI-friendly features
    agentFriendly: Boolean,       // Has structured data
    hasTimeSeries: Boolean,       // Can be graphed (Grafana)
    hasRichContent: Boolean,      // Has full text/media
    isInteractive: Boolean,       // Supports Q&A (OpenWebUI)
    isStructured: Boolean         // Has structured fields
)
```

## Content Type Matrix

| Type | Human | Agent | TimeSeries | RichContent | Interactive | Structured |
|------|-------|-------|------------|-------------|-------------|------------|
| 📚 BookStack | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| 📰 Article | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| 📈 Market | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| 🌤️ Weather | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| ⚠️ CVE | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| 🌐 Wikipedia | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |
| 📖 Docs | ✅ | ✅ | ❌ | ✅ | ✅ | ❌ |

## API Usage

### Human-Friendly Results
```bash
curl -X POST http://search-service:8098/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "kubernetes deployment",
    "mode": "hybrid",
    "audience": "human"
  }'
```

Returns only results with `humanFriendly: true` (has UI/visualization)

### Agent-Friendly Results
```bash
curl -X POST http://search-service:8098/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "current BTC price",
    "mode": "hybrid",
    "audience": "agent"
  }'
```

Returns only results with `agentFriendly: true` (has structured data)

### Both Audiences
```bash
curl -X POST http://search-service:8098/search \
  -H "Content-Type: application/json" \
  -d '{
    "query": "security vulnerabilities",
    "mode": "hybrid",
    "audience": "both"
  }'
```

Returns all results (default behavior)

## Type-Specific Handlers

### 📚 BookStack Articles
**Human:**
- Inline preview modal
- "Open in BookStack" button
- "Chat with AI" → OpenWebUI

**Agent:**
- Full markdown content
- Metadata: author, created_at, tags
- Link to source

---

### 📰 Articles (RSS/News/ArXiv)
**Human:**
- Article reader modal
- Publication date, source
- "Chat with AI" → OpenWebUI
- "Save to BookStack" option

**Agent:**
- Full article text (cleaned)
- Structured metadata: date, author, source, category
- Original URL

---

### 📈 Market Data
**Human:**
- Embedded Grafana chart
- Current price, % change
- "Export to CSV" | "Analyze in Jupyter"

**Agent:**
- Structured data: `{symbol, price, volume, timestamp}`
- Time-series array for graphing
- API endpoint for live updates

---

### 🌤️ Weather
**Human:**
- Weather card with icon
- Current conditions: temp, humidity, wind
- Visual display

**Agent:**
- Structured JSON: `{location, temp_c, humidity, wind_kph, condition}`
- Latest reading only

---

### ⚠️ CVE/Security
**Human:**
- CVE alert card with severity badge
- CVSS score, affected systems
- Link to BookStack security article
- "Chat about this CVE" → OpenWebUI

**Agent:**
- Structured: `{cve_id, cvss_score, severity, affected_products, description}`
- Mitigation steps
- Link to analysis

---

### 🌐 Wikipedia
**Human:**
- Inline preview (first 2-3 paragraphs)
- "Read full article" → Wikipedia
- "Chat about this" → OpenWebUI

**Agent:**
- Full text content
- Structured metadata
- Links to related topics

---

### 📖 Documentation
**Human:**
- Syntax-highlighted code viewer
- Copy button
- "Chat with docs" → OpenWebUI

**Agent:**
- Raw content (markdown/code)
- Language/framework metadata
- Version info

## Integration Points

### Agent Tool Server
```javascript
// MCP tool: search_knowledge_base
{
  name: "search_knowledge_base",
  description: "Search across all indexed knowledge",
  parameters: {
    query: "string",
    audience: "agent", // Always agent for MCP
    mode: "hybrid"
  }
}
```

### OpenWebUI RAG
```javascript
// OpenWebUI calls search-service for context
POST /search
{
  "query": user_question,
  "audience": "both",
  "mode": "hybrid",
  "limit": 5
}

// Returns top 5 results for RAG context
```

### Grafana Datasource
```javascript
// Custom datasource plugin
// Queries search-service for time-series data
POST /search
{
  "query": "BTC market data",
  "collections": ["market-crypto"],
  "audience": "agent"
}

// Filters to results with hasTimeSeries: true
```

### JupyterHub Integration
```python
# Python library: datamancy_search
from datamancy_search import SearchClient

client = SearchClient("http://search-service:8098")
results = client.search(
    query="BTC price history",
    audience="agent",
    collections=["market-crypto"]
)

# Returns structured data ready for pandas
df = results.to_dataframe()
```

## Future Enhancements

1. **Smart Routing**
   - Auto-detect audience from User-Agent
   - API key → Agent mode
   - Browser → Human mode

2. **Result Ranking by Audience**
   - Humans prefer: recency, visual appeal, interactivity
   - Agents prefer: data completeness, structure, API availability

3. **Multi-Modal Results**
   - Images/charts for humans
   - Tables/JSON for agents

4. **Conversation Context**
   - Remember previous queries
   - Chain searches for agents
   - Maintain chat history for humans

5. **Federated Search**
   - Search across external APIs (GitHub, StackOverflow)
   - Unified interface for all knowledge sources

## Architecture Benefits

✅ **Single Source of Truth** - All knowledge flows through one service
✅ **Consistent API** - Same interface for humans and agents
✅ **Type Safety** - Capability flags ensure correct usage
✅ **Flexible Rendering** - Frontend decides how to display based on capabilities
✅ **Agent-Ready** - Built for LLM tool calling from day one
✅ **Extensible** - Add new content types without breaking clients

---

**This is the future of knowledge management.** 🚀
