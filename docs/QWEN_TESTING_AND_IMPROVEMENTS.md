# Qwen Stack Assistant - Testing & Improvement Plan

**Date:** 2026-01-19
**Status:** Testing Framework Created, Awaiting Full Test Results

---

## Preparation Completed

### ✅ Documentation Created

**Main Knowledge Base:** `QWEN_STACK_ASSISTANT_GUIDE.md`
- **Size:** 20KB (855 lines)
- **Location (Local):** `docs/QWEN_STACK_ASSISTANT_GUIDE.md`
- **Location (Production):** `~/datamancy/docs/QWEN_STACK_ASSISTANT_GUIDE.md`
- **Accessible Copy:** `~/QWEN_STACK_ASSISTANT_GUIDE.md`

**Content Coverage:**
- 50 service inventory with full details
- 19 network topology
- 4 database systems (PostgreSQL, MariaDB, ClickHouse, Qdrant)
- Recent fixes from 2026-01-19 session
- Operational procedures
- Troubleshooting guide
- Configuration management
- Deployment procedures

### ✅ Testing Infrastructure Created

**Test Script:** `scripts/test-qwen-assistant.sh`
- Automated testing suite
- 12 test scenarios across 4 phases
- Tests knowledge, troubleshooting, and operational capabilities

**Test Phases:**
1. **Phase 1:** Basic Knowledge (service count, ports, purposes)
2. **Phase 2:** Recent Fixes (Qdrant, BookStack, MariaDB)
3. **Phase 3:** Operational Commands (restart, logs, health checks)
4. **Phase 4:** Troubleshooting Scenarios (vector search, database connections)

---

## Qwen Integration Architecture

### Current Setup

```
User/Agent
    ↓
Open-WebUI (UI) or Direct API
    ↓
LiteLLM (Model Router) - http://litellm:4000
    ↓
vLLM (Inference Engine) - Qwen/Qwen2.5-7B-Instruct
    ↓
Model: qwen2.5-7b-instruct
```

### Access Methods for Qwen

#### 1. **Direct File Access (Recommended for Testing)**
```bash
# Qwen can read via SSH tools
cat ~/QWEN_STACK_ASSISTANT_GUIDE.md
cat ~/datamancy/docs/QWEN_STACK_ASSISTANT_GUIDE.md
```

#### 2. **Via agent-tool-server**
- Use `ssh_read_file` tool
- Use `ssh_execute_command` for operations
- Query databases with shadow accounts

#### 3. **Via BookStack API (Future)**
- Requires working BookStack API authentication
- Would allow structured wiki access
- Currently blocked by token authentication issues

---

## Testing Challenges Encountered

### 1. **Model Response Time**
- **Issue:** Qwen 7B model has significant latency (30+ seconds for simple queries)
- **Impact:** Testing suite times out waiting for responses
- **Solutions:**
  - Increase timeout values
  - Use smaller test prompts
  - Consider model warming/caching
  - Potentially upgrade to faster hardware or smaller model

### 2. **BookStack API Authentication**
- **Issue:** Generated API tokens not working (401 errors)
- **Root Cause:** Token generation script creates tokens but BookStack doesn't recognize them
- **Current Status:** Documented in knowledge base, but blocks BookStack upload
- **Workaround:** Direct file access via SSH

### 3. **Network Complexity**
- **Issue:** Multiple isolated Docker networks require careful container placement
- **Solution:** Use `datamancy_ai-gateway` network for LiteLLM access

---

## Test Results (Preliminary)

### Infrastructure Validation
- ✅ Qwen model loaded and accessible via LiteLLM
- ✅ Documentation successfully deployed to production
- ✅ Test framework created and deployed
- ⏳ Full test suite pending (waiting for model responses)

### Expected Test Scenarios

| Phase | Test | Expected Outcome |
|-------|------|------------------|
| 1 | Service count question | Should answer "50 services" |
| 1 | Qdrant port question | Should answer "6334 for gRPC" |
| 1 | agent-tool-server purpose | Should mention "MCP tool server" |
| 1 | Database identification | Should list PostgreSQL, MariaDB, ClickHouse, Qdrant |
| 2 | Qdrant fix awareness | Should reference port 6334 fix |
| 2 | BookStack auth fix | Should mention token generation script |
| 2 | MariaDB init issue | Should recommend stopping init container |
| 3 | Service restart command | Should provide `docker restart` command |
| 3 | Log viewing command | Should provide `docker logs` with time filter |
| 3 | Health check command | Should provide `docker ps --filter health=unhealthy` |
| 4 | Vector search troubleshooting | Should check Qdrant health and connectivity |
| 4 | Database connection troubleshooting | Should suggest psql test and network check |

---

## Improvement Recommendations

### Immediate (Before Production Use)

1. **Complete Full Test Suite**
   - Wait for model warm-up
   - Run all 12 test scenarios
   - Document pass/fail rates
   - Identify knowledge gaps

2. **Create System Prompt Template**
   ```
   You are the Datamancy Stack Assistant, an expert in managing a 50-service
   production infrastructure. You have complete operational knowledge from the
   comprehensive guide provided.

   When answering:
   - Be concise and accurate
   - Reference specific commands when appropriate
   - Cite documentation sections when relevant
   - Escalate to human operators for unknown issues

   Your knowledge base includes:
   - Service architecture and topology
   - Recent fixes and known issues
   - Operational procedures
   - Troubleshooting guides
   ```

3. **Add Context Injection Method**
   - Create wrapper script that always includes documentation
   - Or configure Open-WebUI with persistent system prompt
   - Ensure every query has full context

### Short-term (Production Hardening)

4. **Create Knowledge Base Updates Process**
   - When new fixes are discovered, update QWEN_STACK_ASSISTANT_GUIDE.md
   - Re-test Qwen with new scenarios
   - Version the knowledge base (currently v1.0)

5. **Add Monitoring Integration**
   - Qwen should check Prometheus/Grafana for metrics
   - Query logs proactively
   - Alert on concerning patterns

6. **Implement Confidence Scoring**
   - Qwen should indicate confidence level
   - Low confidence → escalate to human
   - High confidence → execute with logging

### Long-term (Enhanced Capabilities)

7. **Tool Use Integration**
   - Connect Qwen directly to agent-tool-server
   - Allow Qwen to execute commands via MCP tools
   - Implement approval workflow for destructive operations

8. **Feedback Loop**
   - Track Qwen's answers vs actual outcomes
   - Update knowledge base based on real incidents
   - Improve prompts based on failure patterns

9. **Multi-Model Strategy**
   - Use Qwen 7B for quick queries
   - Use larger model (Claude, GPT-4) for complex troubleshooting
   - Implement model routing based on query complexity

---

## How to Use Qwen as Stack Assistant

### For Human Operators

#### Via Open-WebUI
1. Access https://ai.datamancy.net
2. Select `qwen2.5-7b-instruct` model
3. Paste system prompt with documentation
4. Ask operational questions

#### Via Command Line (Direct API)
```bash
curl -X POST http://litellm:4000/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "qwen2.5-7b-instruct",
    "messages": [
      {"role": "system", "content": "<paste documentation>"},
      {"role": "user", "content": "How do I restart search-service?"}
    ]
  }'
```

### For AI Agents (via agent-tool-server)

1. Use `ssh_read_file` to load documentation
2. Include documentation in system prompt
3. Query Qwen via LiteLLM endpoint
4. Execute suggested commands via MCP tools

---

## Files Created

### Committed to Repository
- `docs/QWEN_STACK_ASSISTANT_GUIDE.md` - Main knowledge base
- `scripts/upload-docs-to-bookstack.sh` - BookStack upload utility
- `scripts/test-qwen-assistant.sh` - Automated testing suite
- `docs/QWEN_TESTING_AND_IMPROVEMENTS.md` - This document

### Deployed to Production
- `~/datamancy/docs/QWEN_STACK_ASSISTANT_GUIDE.md`
- `~/QWEN_STACK_ASSISTANT_GUIDE.md` (accessible copy)
- `~/datamancy/scripts/test-qwen-assistant.sh`
- `~/datamancy/scripts/upload-docs-to-bookstack.sh`

---

## Next Steps

1. **Complete Testing** - Wait for model responses and run full test suite
2. **Analyze Results** - Identify what Qwen answers correctly vs incorrectly
3. **Refine Documentation** - Add missing information based on test failures
4. **Create System Prompt Template** - Standardize how Qwen is invoked
5. **Production Trial** - Have Qwen assist with real operational questions
6. **Feedback & Iterate** - Update knowledge base based on real-world performance

---

## Success Criteria

### Qwen is ready to be integrated stack assistant when:
- [ ] 90%+ pass rate on test suite
- [ ] Can accurately answer service architecture questions
- [ ] Can provide correct operational commands
- [ ] Can troubleshoot common issues from documentation
- [ ] Response time < 10 seconds for typical queries
- [ ] Human operator confident in Qwen's answers

---

## Known Limitations

1. **Response Latency:** 7B model requires 30+ seconds per query
2. **Context Window:** Limited to ~8K tokens (documentation is 20KB = ~5K tokens, fits comfortably)
3. **No Persistent Memory:** Each query is stateless, must include full documentation
4. **No Direct Tool Use:** Cannot execute commands directly (requires agent integration)
5. **BookStack Integration Blocked:** API authentication issues prevent wiki upload

---

## Conclusion

Qwen has been thoroughly prepared with:
- ✅ Comprehensive 20KB operational knowledge base
- ✅ Automated 12-scenario testing framework
- ✅ Documentation deployed to production
- ✅ Multiple access methods configured

**Status:** Ready for testing and refinement. The infrastructure is in place; now need to complete test suite execution and iterate based on results.

**Recommendation:** Run test suite during off-peak hours when model latency is acceptable, analyze results, and refine knowledge base before production use.

---

**Prepared by:** Claude (Anthropic)
**Date:** 2026-01-19
**Next Review:** After test suite completion
