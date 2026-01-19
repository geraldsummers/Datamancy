# Stack Health Monitor Microservice Design

**Status:** Proposed
**Priority:** Phase 2 (after standalone scripts working)

## Overview

Lightweight microservice that continuously monitors Datamancy stack health by running integration tests and exposing metrics.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Stack Health Monitor Container                         │
│  (Kotlin/Ktor HTTP server)                              │
│                                                           │
│  ┌──────────────────────────────────────────────────┐  │
│  │ Test Scheduler                                    │  │
│  │ • Runs tests every 5-30 min                       │  │
│  │ • Stores results in-memory (last 100)             │  │
│  │ • Triggers alerts on failures                     │  │
│  └──────────────────────────────────────────────────┘  │
│                                                           │
│  ┌──────────────────────────────────────────────────┐  │
│  │ HTTP Endpoints                                    │  │
│  │ • GET  /health                                    │  │
│  │ • GET  /metrics (Prometheus format)               │  │
│  │ • GET  /test-results                              │  │
│  │ • POST /run-tests (manual trigger)                │  │
│  │ • GET  /dashboard (simple HTML status page)       │  │
│  └──────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
         │
         ├─→ agent-tool-server (runs tests)
         ├─→ litellm (LLM tests)
         ├─→ postgres (DB tests)
         └─→ ntfy (sends alerts)
```

## Network Placement

**Place in:** `ai-gateway` network
- Has controlled access to agent-tool-server
- Can reach search-service
- Cannot directly access databases (goes through agent-tool-server tools)

## Implementation

### Service Definition

```yaml
# In services.registry.yaml or compose file

datamancy:
  stack-health-monitor:
    image: datamancy/stack-health-monitor
    version: local-build
    container_name: stack-health-monitor
    subdomain: stack-health
    networks:
      - caddy          # For web UI access
      - ai-gateway     # For testing agent-tool-server
      - monitoring     # For Prometheus scraping
    environment:
      AGENT_TOOL_SERVER_URL: "http://agent-tool-server:8081"
      LITELLM_URL: "http://litellm:4000"
      TEST_INTERVAL_MINUTES: "15"
      NTFY_URL: "http://ntfy:80"
      NTFY_TOPIC: "datamancy-alerts"
    volumes:
      - stack_health_history:/app/data  # Persist last 1000 test results
    health_check:
      type: docker
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 10s
    phase: datamancy
    phase_order: 6
```

### Code Structure

```
src/stack-health-monitor/
├── build.gradle.kts
├── Dockerfile
└── src/main/kotlin/
    ├── Main.kt                      # Entry point
    ├── HealthMonitorServer.kt       # Ktor HTTP server
    ├── TestScheduler.kt             # Runs tests on schedule
    ├── tests/
    │   ├── FoundationTests.kt       # Reuse from standalone script
    │   ├── DockerTests.kt
    │   ├── LlmTests.kt
    │   ├── KnowledgeBaseTests.kt
    │   └── TestRunner.kt            # Common test framework
    ├── metrics/
    │   ├── MetricsExporter.kt       # Prometheus format
    │   └── TestResultStore.kt       # In-memory storage
    └── alerts/
        └── AlertManager.kt          # Send to ntfy/webhook
```

### Key Features

#### 1. Metrics Endpoint (Prometheus)

```kotlin
// GET /metrics
// Prometheus format for Grafana scraping

stack_health_test_total{suite="foundation",status="passed"} 150
stack_health_test_total{suite="foundation",status="failed"} 2
stack_health_test_duration_seconds{suite="docker",quantile="0.5"} 2.3
stack_health_test_duration_seconds{suite="docker",quantile="0.95"} 5.1
stack_health_last_run_timestamp_seconds 1705584123
stack_health_tests_running{suite="llm"} 1
stack_health_alert_sent_total{severity="critical"} 3
```

#### 2. Dashboard Endpoint

```kotlin
// GET /dashboard
// Simple HTML status page (no framework needed)

<!DOCTYPE html>
<html>
<head><title>Datamancy Stack Health</title></head>
<body>
  <h1>Stack Health Monitor</h1>
  <div class="status">
    <h2>Last Test Run: 2026-01-18 10:30:00 UTC</h2>
    <table>
      <tr><th>Suite</th><th>Status</th><th>Duration</th><th>Details</th></tr>
      <tr>
        <td>Foundation</td>
        <td>✅ PASS</td>
        <td>1.2s</td>
        <td><a href="/test-results/foundation">View</a></td>
      </tr>
      <tr>
        <td>Docker</td>
        <td>✅ PASS</td>
        <td>8.5s</td>
        <td><a href="/test-results/docker">View</a></td>
      </tr>
      <tr>
        <td>LLM</td>
        <td>❌ FAIL</td>
        <td>15.2s</td>
        <td>2/5 tests failed - <a href="/test-results/llm">Details</a></td>
      </tr>
    </table>
  </div>
  <button onclick="fetch('/run-tests', {method:'POST'})">
    🔄 Run Tests Now
  </button>
</body>
</html>
```

#### 3. Alert Integration

```kotlin
class AlertManager(private val ntfyUrl: String, private val topic: String) {

    suspend fun sendAlert(testResult: TestResult.Failure) {
        val priority = when {
            testResult.name.contains("foundation") -> "urgent"
            testResult.name.contains("llm") -> "high"
            else -> "default"
        }

        httpClient.post("$ntfyUrl/$topic") {
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "title" to "Stack Health Alert",
                "message" to "${testResult.name} failed: ${testResult.error}",
                "priority" to priority,
                "tags" to listOf("warning", "datamancy"),
                "actions" to listOf(
                    mapOf(
                        "action" to "view",
                        "label" to "View Dashboard",
                        "url" to "https://stack-health.datamancy.net/dashboard"
                    )
                )
            ))
        }
    }
}
```

#### 4. Test Scheduler

```kotlin
class TestScheduler(
    private val intervalMinutes: Long,
    private val testRunner: TestRunner,
    private val metricsStore: TestResultStore,
    private val alertManager: AlertManager
) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        scope.launch {
            while (true) {
                try {
                    logger.info("Starting scheduled test run")
                    val results = runAllTests()

                    // Store results
                    metricsStore.addTestRun(results)

                    // Send alerts for failures
                    results.filterIsInstance<TestResult.Failure>().forEach { failure ->
                        alertManager.sendAlert(failure)
                    }

                } catch (e: Exception) {
                    logger.error("Test run failed", e)
                }

                delay(intervalMinutes * 60 * 1000)
            }
        }
    }

    suspend fun runAllTests(): List<TestResult> {
        return listOf(
            testRunner.runFoundationTests(),
            testRunner.runDockerTests(),
            testRunner.runLlmTests(),
            testRunner.runKnowledgeBaseTests()
        ).flatten()
    }
}
```

## Grafana Integration

### Add Dashboard

Create `configs/monitoring/grafana/provisioning/dashboards/stack-health.json`:

```json
{
  "dashboard": {
    "title": "Datamancy Stack Health",
    "panels": [
      {
        "title": "Test Success Rate",
        "targets": [
          {
            "expr": "rate(stack_health_test_total{status=\"passed\"}[5m]) / rate(stack_health_test_total[5m])"
          }
        ]
      },
      {
        "title": "Test Duration (p95)",
        "targets": [
          {
            "expr": "stack_health_test_duration_seconds{quantile=\"0.95\"}"
          }
        ]
      },
      {
        "title": "Alerts Sent",
        "targets": [
          {
            "expr": "increase(stack_health_alert_sent_total[1h])"
          }
        ]
      }
    ]
  }
}
```

### Add Prometheus Scrape Target

Edit `configs/monitoring/prometheus/prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'stack-health-monitor'
    static_configs:
      - targets: ['stack-health-monitor:8080']
    scrape_interval: 30s
```

## Deployment

### Add to docker-compose

The service gets added automatically when you run `build-datamancy.main.kts` since it's in the services registry.

### Start Monitoring

```bash
cd ~/.datamancy

# Start health monitor
docker compose up -d stack-health-monitor

# Check logs
docker logs -f stack-health-monitor

# View dashboard
open https://stack-health.datamancy.net/dashboard

# View metrics
curl https://stack-health.datamancy.net/metrics
```

## Configuration

### Environment Variables

```bash
# In .env or docker-compose.yml

# Test execution
TEST_INTERVAL_MINUTES=15           # Run tests every 15 minutes
TEST_TIMEOUT_SECONDS=60            # Individual test timeout
DOCKER_TEST_ENABLED=true           # Enable Docker tests
LLM_TEST_ENABLED=true              # Enable LLM tests
KNOWLEDGE_BASE_TEST_ENABLED=true   # Enable DB tests

# Alerting
NTFY_URL=http://ntfy:80
NTFY_TOPIC=datamancy-alerts
ALERT_ON_FAILURE_ONLY=true         # Don't alert on success
ALERT_COOLDOWN_MINUTES=60          # Don't spam alerts

# Storage
TEST_HISTORY_LIMIT=1000            # Keep last N test runs
METRICS_RETENTION_HOURS=168        # Keep metrics for 1 week
```

## Resource Requirements

```yaml
deploy:
  resources:
    limits:
      memory: 256M      # Lightweight - just runs HTTP tests
      cpus: '0.5'
    reservations:
      memory: 128M
      cpus: '0.25'
```

## Benefits Over Standalone Scripts

| Feature | Standalone Scripts | Health Monitor Service |
|---------|-------------------|------------------------|
| Manual execution | ✅ Easy | ❌ Needs API call |
| Continuous monitoring | ❌ Manual cron | ✅ Built-in scheduler |
| Historical data | ❌ No storage | ✅ Time-series metrics |
| Alerting | ❌ Manual | ✅ Automatic |
| Grafana integration | ❌ No | ✅ Prometheus metrics |
| Resource overhead | ✅ Zero when not running | ⚠️ Always running |
| Deployment complexity | ✅ Just a script | ⚠️ Another service |

## Recommendation

**Use Both:**
1. **Standalone scripts** for development, debugging, CI/CD
2. **Health monitor service** for production monitoring

Start with standalone scripts (Phase 1), add monitor service later (Phase 2).

## Implementation Timeline

- **Phase 1 (Now):** Standalone scripts working
- **Phase 2 (Week 2):** Basic health monitor service
- **Phase 3 (Week 3):** Grafana dashboards
- **Phase 4 (Week 4):** Advanced alerting rules

## See Also

- `scripts/stack-health/test-agent-stack.main.kts` - Standalone test script
- `scripts/stack-health/README.md` - Usage documentation
- `docs/monitoring/grafana-setup.md` - Grafana configuration
